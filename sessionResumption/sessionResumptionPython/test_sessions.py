"""Offline tests of the three session options. A scripted fake client replaces the API."""

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace as NS

import main
import sessions
from agent import run_turn


class FakeClient:
    """Answers with a scripted list of responses: each is a list of blocks plus a stop_reason."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.messages = self

    def create(self, **kwargs):
        content, stop = self.responses.pop(0)
        return NS(content=content, stop_reason=stop)


def read(path, id_="t1"):
    return [NS(type="tool_use", id=id_, name="read_file", input={"path": path})], "tool_use"


def answer(obj):
    return [NS(type="text", text=json.dumps(obj))], "end_turn"


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        sessions.SESSIONS_DIR = Path(self.tmp.name) / "sessions"
        main.WORKSPACE = Path(self.tmp.name) / "workspace"
        main.reset_workspace()

    def tearDown(self):
        self.tmp.cleanup()

    def day_one(self):
        """A day-1 session that read the ORIGINAL auth.js."""
        session = sessions.start("review")
        run_turn(session, "review", {}, FakeClient(read("auth.js"), answer({"files": []})), main.WORKSPACE)
        return session


class Resume(Base):
    def test_resume_restores_the_whole_history_including_old_tool_results(self):
        self.day_one()
        main.apply_fixes()                                           # auth.js changes on disk...
        resumed = sessions.resume("review")
        self.assertEqual(len(resumed.messages), 4)                   # user, tool_use, tool_result, answer
        self.assertIn("token_expires_at > Date.now()", resumed.tool_results()[0])  # ...but the history has the OLD one
        self.assertIn("FIXED", (main.WORKSPACE / "auth.js").read_text())


class Fork(Base):
    def test_fork_copies_history_and_leaves_the_original_unchanged(self):
        self.day_one()
        a, b = sessions.fork("review", "a"), sessions.fork("review", "b")
        run_turn(a, "plan A", {}, FakeClient(answer({"plan": ["A"]})), main.WORKSPACE)
        self.assertEqual(len(sessions.resume("review").messages), 4)  # original untouched
        self.assertEqual(len(sessions.resume("a").messages), 6)
        self.assertEqual(len(sessions.resume("b").messages), 4)       # b never sees a's work


class FreshStart(Base):
    def test_fresh_session_has_the_summary_but_no_tool_results(self):
        main.apply_fixes()
        summary = {"files": [{"file": "pricing.js", "issues": [{"line": 12, "description": "Friday missing"}]}]}
        prompt = sessions.summary_injection_prompt(summary, main.CHANGED, main.QUESTION)
        session = sessions.start("day2")
        _, files_read = run_turn(session, prompt, {}, FakeClient(read("auth.js"), answer({})), main.WORKSPACE)
        first = session.messages[0]["content"][0]["text"]
        self.assertIn("Friday missing", first)                       # prior knowledge kept
        self.assertIn("auth.js, database.js, api-routes.js", first)   # targeted re-analysis
        self.assertIn("FIXED", session.tool_results()[0])             # it reads the CURRENT file
        self.assertEqual(files_read, ["auth.js"])


class Classify(unittest.TestCase):
    def test_stale_claims_and_kept_bugs(self):
        status = {"open_bugs": [{"file": "auth.js", "line": 16, "description": "fixed expiry bug"},
                                {"file": "auth.js", "line": 12, "description": "a different, real issue"},
                                {"file": "pricing.js", "line": 12, "description": "y"}],
                  "verified_fixed": [], "fix_first": ""}
        self.assertEqual(main.classify(status), {"stale": ["auth.js:16"], "kept": ["pricing.js"], "verified_fixed": 0})


if __name__ == "__main__":
    unittest.main()
