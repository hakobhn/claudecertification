"""Offline tests: a fake LLM answers by role (which system prompt it was given)."""

import json
import logging
import threading
import unittest

import coordinator as c
from subagents import DOCUMENT_ANALYSIS_SYSTEM, WEB_SEARCH_SYSTEM

SIX = ["Solar power", "Wind power", "Geothermal energy", "Tidal and wave energy", "Biomass energy", "Nuclear fusion"]


def subtopics_json(names):
    return json.dumps({"subtopics": [
        {"name": n, "description": f"Scope of {n}", "search_query": f"{n} overview"} for n in names]})


def evaluation_json(statuses, missing_areas=()):
    return json.dumps({
        "assessments": [{"subtopic": n, "status": s, "gap": "" if s == "covered" else f"{n} lacks cost data",
                         "follow_up_query": "" if s == "covered" else f"{n} cost per MWh"}
                        for n, s in statuses.items()],
        "missing_areas": [{"name": n, "description": f"Scope of {n}", "search_query": f"{n} overview"}
                          for n in missing_areas],
    })


class FakeLLM:
    """Thread-safe fake. Scripts are lists consumed in order, one per coordinator task."""

    def __init__(self, decompositions, evaluations):
        self.decompositions = list(decompositions)
        self.evaluations = list(evaluations)
        self.calls = []  # (system, prompt, schema)
        self._lock = threading.Lock()

    def complete(self, system, prompt, schema=None):
        with self._lock:
            self.calls.append((system, prompt, schema))
            if system == WEB_SEARCH_SYSTEM:
                subtopic = prompt.split("Your assigned subtopic: ")[1].split("\n")[0]
                return f"- web fact about {subtopic}"
            if system == DOCUMENT_ANALYSIS_SYSTEM:
                return "- document fact"
            if schema is c.DECOMPOSE_SCHEMA:
                return self.decompositions.pop(0)
            if schema is c.EVALUATE_SCHEMA:
                return self.evaluations.pop(0)
            return "# Report\n" + "\n".join(f"## {n}\ntext" for n in SIX)

    def calls_for(self, system):
        return [(s, p) for s, p, _ in self.calls if s == system]


def all_covered(names=SIX):
    return evaluation_json({n: "covered" for n in names})


class CoordinatorTests(unittest.TestCase):
    def setUp(self):
        logging.disable(logging.CRITICAL)

    def tearDown(self):
        logging.disable(logging.NOTSET)

    def test_narrow_decomposition_is_rejected_and_retried(self):
        llm = FakeLLM([subtopics_json(["Solar power", "Wind power"]), subtopics_json(SIX)], [all_covered()])
        report = c.Coordinator(llm).run("renewable energy technologies")

        self.assertEqual([r.subtopic.name for r in report.research], SIX)
        retry_prompt = [p for _, p, s in llm.calls if s is c.DECOMPOSE_SCHEMA][1]
        self.assertIn("too narrow", retry_prompt)

    def test_missing_required_category_is_sent_back_to_claude(self):
        five = SIX[:5]  # enough subtopics, but no fusion - the lesson's narrow-decomposition failure
        llm = FakeLLM([subtopics_json(five), subtopics_json(SIX)], [all_covered()])
        report = c.Coordinator(llm).run("renewable energy technologies", must_cover=c.REQUIRED_FOR_TEST)

        retry_prompt = [p for _, p, s in llm.calls if s is c.DECOMPOSE_SCHEMA][1]
        self.assertIn("does not cover these required categories: fusion", retry_prompt)
        self.assertIn("Nuclear fusion", [r.subtopic.name for r in report.research])

    def test_coordinator_adds_required_category_as_last_resort(self):
        five = SIX[:5]
        llm = FakeLLM([subtopics_json(five), subtopics_json(five)], [all_covered(five + ["Fusion"])])
        report = c.Coordinator(llm).run("renewable energy technologies", must_cover=c.REQUIRED_FOR_TEST)

        self.assertEqual([r.subtopic.name for r in report.research], five + ["Fusion"])
        fusion_prompt = [p for _, p in llm.calls_for(WEB_SEARCH_SYSTEM) if "subtopic: Fusion" in p]
        self.assertEqual(len(fusion_prompt), 1)  # it really was researched

    def test_subagents_are_isolated_and_get_explicit_context(self):
        llm = FakeLLM([subtopics_json(SIX)], [all_covered()])
        c.Coordinator(llm).run("renewable energy technologies")

        for system, prompt in llm.calls_for(WEB_SEARCH_SYSTEM) + llm.calls_for(DOCUMENT_ANALYSIS_SYSTEM):
            self.assertNotIn(c.COORDINATOR_SYSTEM, prompt)        # no inherited coordinator prompt
            self.assertIn("Overall research topic: renewable energy technologies", prompt)
            self.assertIn("Research goal:", prompt)

        # Each web search prompt mentions only its own subtopic, never another subagent's output.
        solar = [p for _, p in llm.calls_for(WEB_SEARCH_SYSTEM) if "subtopic: Solar power" in p][0]
        self.assertNotIn("web fact about Wind power", solar)

        # Document analysis receives the web findings - passed by the coordinator.
        geo = [p for _, p in llm.calls_for(DOCUMENT_ANALYSIS_SYSTEM) if "subtopic: Geothermal" in p][0]
        self.assertIn("web fact about Geothermal energy", geo)
        self.assertIn("Geothermal Feasibility Study", geo)

    def test_document_analysis_only_where_documents_exist(self):
        llm = FakeLLM([subtopics_json(SIX)], [all_covered()])
        report = c.Coordinator(llm).run("renewable energy technologies")

        analysed = sorted(p.split("Your assigned subtopic: ")[1].split("\n")[0]
                          for _, p in llm.calls_for(DOCUMENT_ANALYSIS_SYSTEM))
        self.assertEqual(analysed, ["Biomass energy", "Geothermal energy", "Solar power", "Wind power"])
        self.assertEqual(len(llm.calls_for(WEB_SEARCH_SYSTEM)), 6)
        self.assertEqual(report.coverage, 1.0)

    def test_refinement_loop_redelegates_only_the_gaps(self):
        first = {n: "covered" for n in SIX} | {"Tidal and wave energy": "partial"}
        llm = FakeLLM(
            [subtopics_json(SIX)],
            [evaluation_json(first, missing_areas=["Hydropower"]), all_covered(SIX + ["Hydropower"])],
        )
        report = c.Coordinator(llm).run("renewable energy technologies")

        self.assertEqual(report.rounds, 2)
        self.assertEqual(report.coverage, 1.0)
        web_prompts = [p for _, p in llm.calls_for(WEB_SEARCH_SYSTEM)]
        self.assertEqual(len(web_prompts), 8)  # 6 initial + tidal follow-up + new hydropower area

        tidal_follow_up = [p for p in web_prompts if "Tidal" in p and "Gap the coordinator" in p][0]
        self.assertIn("Search focus: Tidal and wave energy cost per MWh", tidal_follow_up)
        self.assertIn("web fact about Tidal and wave energy", tidal_follow_up)  # prior findings passed back in
        self.assertIn("Hydropower", [r.subtopic.name for r in report.research])

    def test_refinement_stops_at_max_rounds(self):
        never_done = evaluation_json({n: "partial" for n in SIX})
        llm = FakeLLM([subtopics_json(SIX)], [never_done] * 5)
        report = c.Coordinator(llm, max_rounds=3).run("renewable energy technologies")

        self.assertEqual(report.rounds, 3)
        self.assertEqual(len(llm.calls_for(WEB_SEARCH_SYSTEM)), 18)  # 3 rounds x 6 subtopics
        self.assertIn("Coverage: 0/6", report.markdown)

    def test_all_messages_route_through_the_coordinator(self):
        llm = FakeLLM([subtopics_json(SIX)], [all_covered()])
        coord = c.Coordinator(llm)
        coord.run("renewable energy technologies")

        self.assertTrue(coord.trace)
        for sender, receiver, _ in coord.trace:
            self.assertIn("coordinator", (sender, receiver))  # hub on every edge, no spoke-to-spoke

    def test_failed_subagent_is_retried_then_recorded(self):
        llm = FakeLLM([subtopics_json(SIX)], [all_covered()])
        real = llm.complete
        failures = {"n": 0}

        def flaky(system, prompt, schema=None):
            if system == WEB_SEARCH_SYSTEM and "Nuclear fusion" in prompt and failures["n"] < 1:
                failures["n"] += 1
                raise RuntimeError("overloaded")
            return real(system, prompt, schema)

        llm.complete = flaky
        report = c.Coordinator(llm).run("renewable energy technologies")
        fusion = [r for r in report.research if r.subtopic.name == "Nuclear fusion"][0]
        self.assertEqual(fusion.findings, [("web_search", "- web fact about Nuclear fusion")])


if __name__ == "__main__":
    unittest.main()
