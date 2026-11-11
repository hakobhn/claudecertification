"""Offline tests for the agentic loop: a scripted fake client stands in for the API."""

import json
import logging
import unittest
from types import SimpleNamespace as NS

import agent


def text(t):
    return NS(type="text", text=t)


def tool_use(id_, name, inp):
    return NS(type="tool_use", id=id_, name=name, input=inp)


def resp(stop_reason, *blocks):
    return NS(stop_reason=stop_reason, content=list(blocks), stop_details=None)


class FakeClient:
    """Returns scripted responses and records the messages sent on each call."""

    def __init__(self, responses):
        self._responses = iter(responses)
        self.calls = []
        self.messages = self

    def create(self, **kwargs):
        self.calls.append(json.loads(json.dumps(kwargs["messages"], default=vars)))
        return next(self._responses)


class AgentLoopTests(unittest.TestCase):
    def test_tools(self):
        self.assertEqual(json.loads(agent.calculator("(8849 - 6190) * 2"))["result"], 5318)
        self.assertIn("8,849", agent.web_search("height of Everest"))
        content, is_error = agent.execute_tool("calculator", {"expression": "__import__('os')"})
        self.assertTrue(is_error)

    def test_sequential_tool_calls_until_end_turn(self):
        # Text + tool_use in the same response must NOT end the loop (anti-pattern 3).
        client = FakeClient([
            resp("tool_use", text("Let me look up both heights."),
                 tool_use("t1", "web_search", {"query": "Everest height"}),
                 tool_use("t2", "web_search", {"query": "Denali height"})),
            resp("tool_use", text("Now I'll compute the difference."),
                 tool_use("t3", "calculator", {"expression": "8849 - 6190"})),
            resp("end_turn", text("Everest is 2,659 m taller than Denali.")),
        ])
        out = agent.run_agent("How much taller is Everest than Denali?", client=client)

        self.assertEqual(out, "Everest is 2,659 m taller than Denali.")
        self.assertEqual(len(client.calls), 3)  # two tool iterations, then end_turn

        # History grows: user, assistant(tool_use), user(tool_results), ...
        second = client.calls[1]
        self.assertEqual([m["role"] for m in second], ["user", "assistant", "user"])
        results = second[2]["content"]
        self.assertEqual([r["tool_use_id"] for r in results], ["t1", "t2"])  # one message, both results
        self.assertIn("8,849", results[0]["content"])

        third = client.calls[2]
        self.assertEqual(json.loads(third[4]["content"][0]["content"])["result"], 2659)

    def test_tool_error_is_returned_to_claude(self):
        client = FakeClient([
            resp("tool_use", tool_use("t1", "calculator", {"expression": "1/0"})),
            resp("end_turn", text("Division by zero is undefined.")),
        ])
        agent.run_agent("1/0?", client=client)
        result = client.calls[1][2]["content"][0]
        self.assertTrue(result["is_error"])
        self.assertIn("ZeroDivisionError", result["content"])

    def test_safety_cap_is_backstop_and_warns(self):
        looping = [resp("tool_use", tool_use(f"t{i}", "web_search", {"query": "x"}))
                   for i in range(agent.MAX_ITERATIONS + 5)]
        client = FakeClient(looping)
        with self.assertLogs("agent", level=logging.WARNING) as logs:
            out = agent.run_agent("loop forever", client=client)
        self.assertEqual(len(client.calls), agent.MAX_ITERATIONS)
        self.assertIn("safety", out)
        self.assertTrue(any("Safety cap" in m for m in logs.output))

    def test_other_stop_reasons_are_not_treated_as_done(self):
        for reason in ("max_tokens", "refusal", "model_context_window_exceeded"):
            with self.subTest(reason=reason):
                client = FakeClient([resp(reason, text("partial"))])
                with self.assertRaises(RuntimeError):
                    agent.run_agent("q", client=client)


if __name__ == "__main__":
    unittest.main()
