"""Offline tests. The gate and handoff check are tested directly (no model), and the
whole loop is tested with a scripted fake client that misbehaves on purpose."""

import json
import logging
import unittest
from types import SimpleNamespace as NS

import agent
from tools import SupportSession, validate_handoff

COMPLETE_HANDOFF = {
    "customer_id": "C-1001",
    "conversation_summary": "Customer asked to return O-5001, reported a duplicate charge on O-5002 and "
                            "asked to change her shipping address. Return refund R-0001 (129.00) processed.",
    "root_cause_analysis": "O-5002 shows two identical charges of 89.50: a duplicate capture at checkout.",
    "refund_amount": 89.50,
    "recommended_action": "Refund the duplicate 89.50 charge on O-5002 and update the shipping address "
                          "to 12 Elm Street, Bath BA1 2QQ after confirming with the customer.",
    "concerns": [
        {"concern": "Return O-5001", "status": "resolved", "details": "Refunded 129.00 (R-0001)."},
        {"concern": "Duplicate charge O-5002", "status": "needs_human", "details": "Two charges of 89.50."},
        {"concern": "Address change", "status": "needs_human", "details": "New: 12 Elm Street, Bath BA1 2QQ."},
    ],
}


# --------------------------------------------------------------------------- gate (no model)
class GateTests(unittest.TestCase):
    def setUp(self):
        logging.disable(logging.CRITICAL)

    def tearDown(self):
        logging.disable(logging.NOTSET)

    def refund(self, s, customer="C-1001", order="O-5001", amount=129.0):
        return s.execute("process_refund", {"customer_id": customer, "order_id": order, "amount": amount})

    def test_refund_blocked_before_verification(self):
        s = SupportSession()
        content, is_error = self.refund(s)
        self.assertTrue(is_error)
        self.assertIn("BLOCKED", content)
        self.assertIn("get_customer", content)  # the error tells the model what to do
        self.assertEqual(s.refunds, [])

    def test_refund_allowed_after_verification(self):
        s = SupportSession()
        s.execute("get_customer", {"name_or_email": "maria.lopez@example.com"})
        content, is_error = self.refund(s)
        self.assertFalse(is_error)
        self.assertEqual(s.refunds[0]["amount"], 129.0)

    def test_unverified_account_stays_blocked(self):
        s = SupportSession()
        s.execute("get_customer", {"name_or_email": "James Chen"})  # identity_verified = False
        content, is_error = self.refund(s, "C-1002", "O-5003", 45.0)
        self.assertTrue(is_error)
        self.assertIn("could not be verified", content)
        self.assertIn("escalate", content)
        self.assertEqual(s.refunds, [])

    def test_refund_only_for_the_verified_customer(self):
        s = SupportSession()
        s.execute("get_customer", {"name_or_email": "maria.lopez@example.com"})
        content, is_error = self.refund(s, "C-1002", "O-5003", 45.0)
        self.assertTrue(is_error)
        self.assertIn("only go to the verified customer (C-1001)", content)

    def test_business_rules_still_apply_after_the_gate(self):
        s = SupportSession()
        s.execute("get_customer", {"name_or_email": "maria.lopez@example.com"})
        _, is_error = self.refund(s, amount=500.0)
        self.assertTrue(is_error)  # more than the order amount


# --------------------------------------------------------------------------- handoff check
class HandoffTests(unittest.TestCase):
    def test_complete_handoff_passes(self):
        self.assertEqual(validate_handoff(COMPLETE_HANDOFF), [])

    def test_missing_and_placeholder_fields_are_rejected(self):
        bad = dict(COMPLETE_HANDOFF, root_cause_analysis="", recommended_action="TBD", customer_id="[customer]")
        problems = " ".join(validate_handoff(bad))
        self.assertIn("root_cause_analysis", problems)
        self.assertIn("recommended_action", problems)
        self.assertIn("customer_id", problems)

    def test_concerns_need_details(self):
        bad = dict(COMPLETE_HANDOFF, concerns=[{"concern": "Address", "status": "needs_human", "details": "N/A"}])
        self.assertTrue(any("has no details" in p for p in validate_handoff(bad)))
        self.assertTrue(validate_handoff(dict(COMPLETE_HANDOFF, concerns=[])))


# --------------------------------------------------------------------------- whole loop, scripted model
def text(t):
    return NS(type="text", text=t)


def tool_use(id_, name, inp):
    return NS(type="tool_use", id=id_, name=name, input=inp)


def resp(stop_reason, *blocks):
    return NS(stop_reason=stop_reason, content=list(blocks))


class FakeClient:
    def __init__(self, responses):
        self._responses = iter(responses)
        self.requests = []
        self.messages = self

    def create(self, **kwargs):
        self.requests.append(kwargs["messages"][:])
        return next(self._responses)


def tool_results(request):
    return request[-1]["content"]


class LoopTests(unittest.TestCase):
    def setUp(self):
        logging.disable(logging.CRITICAL)

    def tearDown(self):
        logging.disable(logging.NOTSET)

    def test_model_skips_verification_gate_blocks_then_model_recovers(self):
        # The model "decides" to skip verification - exactly the 8% case from the lesson.
        client = FakeClient([
            resp("tool_use", text("Processing your refund now."),
                 tool_use("t1", "process_refund", {"customer_id": "C-1001", "order_id": "O-5001", "amount": 129.0})),
            resp("tool_use", tool_use("t2", "get_customer", {"name_or_email": "maria.lopez@example.com"})),
            resp("tool_use", tool_use("t3", "process_refund", {"customer_id": "C-1001", "order_id": "O-5001", "amount": 129.0})),
            resp("end_turn", text("Your refund of 129.00 has been processed.")),
        ])
        result = agent.run_agent(agent.BYPASS_MESSAGE, client)

        blocked = tool_results(client.requests[1])[0]
        self.assertTrue(blocked["is_error"])
        self.assertIn("BLOCKED", blocked["content"])
        self.assertEqual(len(result.session.blocked), 1)
        self.assertEqual([r["order_id"] for r in result.session.refunds], ["O-5001"])  # only after verification
        self.assertEqual(result.session.refunds[0]["customer_id"], result.session.verified_customer_id)

    def test_multi_concern_parallel_lookups_one_refund_one_complete_handoff(self):
        incomplete = dict(COMPLETE_HANDOFF, recommended_action="")
        client = FakeClient([
            # decompose + investigate in parallel: three tool calls in ONE turn
            resp("tool_use", text("Let me look into all three items."),
                 tool_use("a", "get_customer", {"name_or_email": "maria.lopez@example.com"}),
                 tool_use("b", "lookup_order", {"order_id": "O-5001"}),
                 tool_use("c", "lookup_order", {"order_id": "O-5002"})),
            resp("tool_use", tool_use("d", "process_refund", {"customer_id": "C-1001", "order_id": "O-5001", "amount": 129.0})),
            resp("tool_use", tool_use("e", "escalate_to_human", incomplete)),          # rejected
            resp("tool_use", tool_use("f", "escalate_to_human", COMPLETE_HANDOFF)),    # fixed
            resp("end_turn", text("All three items are handled.")),
        ])
        result = agent.run_agent(agent.MULTI_MESSAGE, client)

        self.assertEqual(len(tool_results(client.requests[1])), 3)  # all parallel results in one message
        rejected = tool_results(client.requests[3])[0]
        self.assertTrue(rejected["is_error"])
        self.assertIn("recommended_action", rejected["content"])

        s = result.session
        self.assertEqual([r["amount"] for r in s.refunds], [129.0])
        self.assertEqual(len(s.handoffs), 1)
        self.assertEqual(len(s.handoffs[0]["concerns"]), 3)
        self.assertEqual(s.handoffs[0]["refund_amount"], 89.50)


if __name__ == "__main__":
    unittest.main()
