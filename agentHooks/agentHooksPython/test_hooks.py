"""Offline tests: the hooks are plain code, so they can be tested without the model."""

import unittest

from hooks import run_with_hooks
from tools import Session

REFUND = {"order_id": "O-7001", "amount": 900.0}
TRANSFER = {"order_id": "O-7002", "sender_id": "S-88", "amount": 900.0}


class PostToolUseNormalisation(unittest.TestCase):
    def test_all_three_formats_become_iso_dates_and_english_statuses(self):
        s = Session()
        customer, _ = run_with_hooks("get_customer", {"customer_id": "C-1001"}, s)
        order, _ = run_with_hooks("lookup_order", {"order_id": "O-7002"}, s)
        shipping, _ = run_with_hooks("check_shipping", {"order_id": "O-7002"}, s)
        lost, _ = run_with_hooks("check_shipping", {"order_id": "O-7001"}, s)

        self.assertEqual(customer["created_at"], "2024-03-15T08:00:00Z")   # was 1710489600
        self.assertEqual(customer["status"], "active")                    # was 200
        self.assertEqual(order["placed_at"], "2024-03-15T12:00:00Z")      # already ISO: unchanged
        self.assertEqual(order["status"], "active")
        self.assertEqual(shipping["shipped_on"], "2024-04-03T00:00:00Z")  # was 03/04/2024 = 3 April
        self.assertEqual(shipping["status"], "delivered")                 # was "D"
        self.assertEqual(lost["status"], "lost in transit")               # was "L"


class PreToolUseRefundLimit(unittest.TestCase):
    def test_refund_over_500_is_denied_before_the_tool_runs(self):
        s = Session()
        content, is_error = run_with_hooks("process_refund", REFUND, s)
        self.assertTrue(is_error)
        self.assertIn("staff approval", content)
        self.assertNotIn("process_refund", s.executed)       # the tool NEVER ran
        self.assertEqual(s.escalations, [REFUND])             # redirected to a human

    def test_refund_up_to_500_is_allowed(self):
        s = Session()
        _, is_error = run_with_hooks("process_refund", dict(REFUND, amount=500.0), s)
        self.assertFalse(is_error)
        self.assertIn("process_refund", s.executed)


class PreToolUseAmlGate(unittest.TestCase):
    def test_transfer_denied_until_aml_passes(self):
        s = Session()
        content, is_error = run_with_hooks("transfer_funds", TRANSFER, s)
        self.assertTrue(is_error)
        self.assertIn("aml_check", content)                  # tells the model what to do
        self.assertNotIn("transfer_funds", s.executed)

        run_with_hooks("aml_check", {"sender_id": "S-88"}, s)  # PostToolUse hook sets aml_passed
        self.assertTrue(s.aml_passed)
        _, is_error = run_with_hooks("transfer_funds", TRANSFER, s)
        self.assertFalse(is_error)
        self.assertEqual(s.executed, ["aml_check", "transfer_funds"])


if __name__ == "__main__":
    unittest.main()
