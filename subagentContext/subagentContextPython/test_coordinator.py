"""Offline tests: a fake client answers as each agent, chosen by the system prompt it receives."""

import json
import logging
import threading
import unittest
from types import SimpleNamespace as NS

import agents as a
import coordinator as c

URL = "https://example.org/offshore-wind-lcoe"
DOC, PAGE = "Offshore Wind Maintenance Report", 3


def text(t):
    return NS(type="text", text=t)


def task(id_, subagent_type):
    return NS(type="tool_use", id=id_, name="Task",
              input={"subagent_type": subagent_type, "description": subagent_type, "prompt": "Research offshore wind costs"})


def tool_use(id_, name, inp):
    return NS(type="tool_use", id=id_, name=name, input=inp)


def resp(stop_reason, *blocks):
    return NS(stop_reason=stop_reason, content=list(blocks))


def finding(claim, url=None, doc=None, page=None, by="web_research"):
    return {"claim": claim, "analysis": "", "source_url": url, "source_title": "t" if url else None,
            "document_name": doc, "page_number": page, "confidence": "high", "retrieved_by": by}


class FakeClient:
    """Plays every agent. The two research agents must be in flight at the same time (a barrier checks it)."""

    def __init__(self, coordinator_script):
        self.coordinator_script = list(coordinator_script)
        self.requests = []  # (system, tool names, first user prompt)
        self.both_research_running = threading.Barrier(2, timeout=5)
        self.lock = threading.Lock()
        self.messages = self

    def create(self, system, messages, tools=(), **_):
        with self.lock:
            self.requests.append((system, [t["name"] for t in tools], messages[0]["content"]))
        first_call = len(messages) == 1

        if system == c.COORDINATOR_SYSTEM:
            with self.lock:
                return self.coordinator_script.pop(0)
        if system == a.WEB_RESEARCH.system_prompt:
            if first_call:
                self.both_research_running.wait()  # fails if the other research agent is not running yet
                return resp("tool_use", tool_use("w1", "web_search", {"query": "offshore wind cost"}))
            return resp("end_turn", text(json.dumps({"findings": [finding("Costs fell 60% since 2012", url=URL)]})))
        if system == a.DOCUMENT_ANALYSIS.system_prompt:
            if first_call:
                self.both_research_running.wait()
                return resp("tool_use", tool_use("d1", "read_document", {"document_name": DOC}))
            return resp("end_turn", text(json.dumps({"findings": [
                finding("Vessel day rates rose 35%", doc=DOC, page=PAGE, by="document_analysis")]})))
        if system == a.SYNTHESIS.system_prompt:
            # Cites exactly what it was given - so missing metadata shows up as missing citations.
            given = json.loads(messages[0]["content"].split("(complete, with source metadata):\n", 1)[1])
            claims = [{"claim": f["claim"], "source_url": f.get("source_url"),
                       "document_name": f.get("document_name"), "page_number": f.get("page_number")} for f in given]
            return resp("end_turn", text(json.dumps({"report": "# Offshore wind\n...", "claims": claims})))
        raise AssertionError("unknown agent")

    def requests_for(self, system):
        return [r for r in self.requests if r[0] == system]


def full_run_script():
    return [
        resp("tool_use", text("Starting both research agents."),
             task("t1", "web_research"), task("t2", "document_analysis")),  # ONE response, two Task calls
        resp("tool_use", task("t3", "synthesis")),
        resp("end_turn", text("The report is ready.")),
    ]


class CoordinatorTests(unittest.TestCase):
    def setUp(self):
        logging.disable(logging.CRITICAL)

    def tearDown(self):
        logging.disable(logging.NOTSET)

    def test_coordinator_needs_task_in_allowed_tools(self):
        client = FakeClient([resp("end_turn", text("I have no way to delegate."))])
        result = c.Coordinator(client, c.CoordinatorOptions(allowed_tools=[])).run("q")

        self.assertEqual(client.requests[0][1], [])  # no Task tool offered
        self.assertEqual(len(client.requests), 1)     # so no subagent ever ran
        self.assertEqual(result.findings, [])

    def test_independent_subagents_run_in_parallel(self):
        client = FakeClient(full_run_script())
        result = c.Coordinator(client).run("q")  # the barrier would time out if they ran one after another

        self.assertEqual(result.spawn_batches, [["web_research", "document_analysis"], ["synthesis"]])

    def test_each_agent_gets_only_its_own_tools(self):
        client = FakeClient(full_run_script())
        c.Coordinator(client).run("q")

        tools = {s: t for s, t, _ in client.requests}
        self.assertEqual(tools[c.COORDINATOR_SYSTEM], ["Task"])
        self.assertEqual(tools[a.WEB_RESEARCH.system_prompt], ["web_search"])
        self.assertEqual(tools[a.DOCUMENT_ANALYSIS.system_prompt], ["list_documents", "read_document"])
        self.assertEqual(tools[a.SYNTHESIS.system_prompt], [])

    def test_subagent_sees_only_its_prompt(self):
        client = FakeClient(full_run_script())
        c.Coordinator(client).run("the coordinator's question")

        _, _, prompt = client.requests_for(a.WEB_RESEARCH.system_prompt)[0]
        self.assertEqual(prompt, "Research offshore wind costs")  # the Task prompt, nothing inherited

    def test_complete_findings_with_metadata_reach_synthesis(self):
        client = FakeClient(full_run_script())
        c.Coordinator(client).run("q")

        _, _, prompt = client.requests_for(a.SYNTHESIS.system_prompt)[0]
        for expected in (URL, DOC, f'"page_number": {PAGE}', "Costs fell", "Vessel day rates"):
            self.assertIn(expected, prompt)

    def test_every_claim_is_attributed(self):
        result = c.Coordinator(FakeClient(full_run_script())).run("q")

        self.assertEqual(len(result.claims), 2)
        self.assertEqual(result.unattributed, [])

    def test_stripped_metadata_breaks_attribution(self):
        # Exam pattern: research worked, synthesis "lost" its sources -> the cause is context passing.
        result = c.Coordinator(FakeClient(full_run_script()), strip_metadata=True).run("q")

        self.assertEqual(len(result.findings), 2)      # the research subagents did their job
        self.assertEqual(len(result.unattributed), 2)  # but no claim can be traced to a source

    def test_subagent_cannot_use_a_tool_outside_its_definition(self):
        run = a.restricted_tool_runner(a.WEB_RESEARCH)
        [result] = run([tool_use("x", "read_document", {"document_name": DOC})])
        self.assertTrue(result["is_error"])

    def test_tools_return_source_metadata(self):
        self.assertIn(URL, a.web_search("offshore wind cost trend"))
        pages = json.loads(a.read_document(DOC))["pages"]
        self.assertEqual([p["page_number"] for p in pages], [1, 2, 3])


if __name__ == "__main__":
    unittest.main()
