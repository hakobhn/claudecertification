"""Offline tests: a fake LLM shows the architecture without API calls."""

import json
import unittest
from pathlib import Path

from compare import SEEDED, score
from review import load_files, multi_pass, single_pass

SAMPLE = Path(__file__).parent.parent / "sample-pr"


class FakeLLM:
    """Records every prompt. Per-file passes 'find' the planted bug of their file."""

    def __init__(self):
        self.prompts = []

    def complete(self, system, prompt, schema):
        self.prompts.append(prompt)
        if "cross_file_issues" in schema["properties"] and "files" not in schema["properties"]:
            return json.dumps({"cross_file_issues": [
                {"files": ["04-basket.js", "05-discounts.js"], "severity": "high", "description": "units"}]})
        if "interface" in schema["properties"]:
            name = prompt.split("=== ")[1].split(" ===")[0]
            found = [{"line": line, "severity": "high", "description": what} for f, line, what in SEEDED if f == name]
            return json.dumps({"findings": found, "interface": [f"exports of {name}"]})
        # single pass: diluted - only the first 5 files get attention
        files = [{"file": f, "findings": [{"line": line, "severity": "high", "description": what}]}
                 for f, line, what in SEEDED if f < "06"]
        return json.dumps({"files": files, "cross_file_issues": []})


class Architecture(unittest.TestCase):
    def setUp(self):
        self.files = load_files(SAMPLE)

    def test_loads_at_least_ten_files_in_order(self):
        self.assertEqual(len(self.files), 14)
        self.assertEqual(list(self.files)[0], "01-auth.js")

    def test_single_pass_puts_every_file_in_one_prompt(self):
        llm = FakeLLM()
        single_pass(self.files, llm)
        self.assertEqual(len(llm.prompts), 1)
        self.assertTrue(all(f"=== {name} ===" in llm.prompts[0] for name in self.files))

    def test_multi_pass_gives_each_file_its_own_prompt_then_one_integration_pass(self):
        llm = FakeLLM()
        multi_pass(self.files, llm, workers=1)
        self.assertEqual(len(llm.prompts), 15)                       # 14 local + 1 integration
        for name, prompt in zip(self.files, llm.prompts):
            headers = [line for line in prompt.splitlines() if line.startswith("=== ")]
            self.assertEqual(headers, [f"=== {name} ==="])             # exactly one file per local pass
            self.assertIn(f"=== {name} ===", prompt)
        integration = llm.prompts[-1]
        self.assertIn("Do NOT repeat local issues", integration)
        self.assertNotIn("  1| ", integration)                        # summaries, not the source code


class Scoring(unittest.TestCase):
    def test_scores_and_artefacts(self):
        files = load_files(SAMPLE)
        single, multi = single_pass(files, FakeLLM()), multi_pass(files, FakeLLM(), workers=1)
        s, m = score(single), score(multi)
        self.assertEqual(len(m["seeded_caught"]), len(SEEDED))
        self.assertLess(len(s["seeded_caught"]), len(SEEDED))
        self.assertEqual(len(m["cross_caught"]), 1)
        # the fake single pass flags getOrder (file 03) but passes identical getWishlist (file 11)
        self.assertIn("missing sign-in check: flagged in 03-orders.js, passed in 11-wishlist.js", s["artefacts"])
        self.assertNotIn("missing sign-in check: flagged in 03-orders.js, passed in 11-wishlist.js", m["artefacts"])


if __name__ == "__main__":
    unittest.main()
