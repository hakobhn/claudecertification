"""Build Exercise 1.2 - Hub-and-spoke research coordinator.

                 ┌──────────────────────┐
                 │     COORDINATOR      │  decomposes, selects subagents,
                 │        (hub)         │  routes context, evaluates coverage,
                 └──────────┬───────────┘  re-delegates gaps, writes the report
           ┌────────────────┴─────────────────┐
   ┌───────▼────────┐                 ┌───────▼─────────┐
   │ web_search     │                 │ document_       │
   │ subagent       │                 │ analysis        │
   └────────────────┘                 └─────────────────┘
   Subagents never talk to each other. Everything goes through the coordinator.

The four coordinator responsibilities from the lesson, and where they are:
  1. Dynamic subagent selection     -> _research_subtopic (documents only where they exist)
  2. Research scope partitioning    -> decompose() + one source type per subagent
  3. Iterative refinement loop      -> run(): evaluate -> re-delegate gaps -> evaluate ...
  4. Centralised routing            -> _route() logs every message in one place
"""

from __future__ import annotations

import logging
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from documents import find_documents
from llm import LLM, ClaudeLLM, complete_json
from subagents import document_analysis_agent, web_search_agent

log = logging.getLogger("coordinator")

MIN_SUBTOPICS = 5
MAX_ROUNDS = 3        # research rounds: 1 initial + up to 2 refinement rounds
MAX_SUBTOPICS = 12    # guard against the evaluator adding areas forever

COORDINATOR_SYSTEM = (
    "You are the coordinator of a hub-and-spoke multi-agent research system. You never research "
    "subtopics yourself. Your jobs are: break a broad topic into subtopics that together cover its "
    "FULL breadth (including less obvious and emerging areas, not just the most popular ones); judge "
    "whether the findings your subagents returned cover each subtopic; and write the final report "
    "using only those findings."
)

DECOMPOSE_SCHEMA = {
    "type": "object",
    "properties": {
        "subtopics": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "description": {"type": "string"},
                    "search_query": {"type": "string"},
                },
                "required": ["name", "description", "search_query"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["subtopics"],
    "additionalProperties": False,
}

EVALUATE_SCHEMA = {
    "type": "object",
    "properties": {
        "assessments": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "subtopic": {"type": "string"},
                    "status": {"type": "string", "enum": ["covered", "partial", "missing"]},
                    "gap": {"type": "string"},
                    "follow_up_query": {"type": "string"},
                },
                "required": ["subtopic", "status", "gap", "follow_up_query"],
                "additionalProperties": False,
            },
        },
        "missing_areas": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "description": {"type": "string"},
                    "search_query": {"type": "string"},
                },
                "required": ["name", "description", "search_query"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["assessments", "missing_areas"],
    "additionalProperties": False,
}


@dataclass
class Subtopic:
    name: str
    description: str
    search_query: str


@dataclass
class SubtopicResearch:
    """Everything the coordinator holds about one subtopic. Only the coordinator sees all of these."""

    subtopic: Subtopic
    findings: list[tuple[str, str]] = field(default_factory=list)  # (agent name, text)
    documents_analysed: bool = False
    status: str = "missing"
    gap: str = ""

    def findings_text(self) -> str:
        return "\n\n".join(f"[{agent}]\n{text}" for agent, text in self.findings)


@dataclass
class FollowUp:
    query: str
    gap: str


@dataclass
class ResearchReport:
    topic: str
    research: list[SubtopicResearch]
    rounds: int
    markdown: str

    @property
    def coverage(self) -> float:
        covered = sum(1 for r in self.research if r.status == "covered")
        return covered / len(self.research) if self.research else 0.0


class Coordinator:
    def __init__(self, llm: LLM, *, min_subtopics: int = MIN_SUBTOPICS, max_rounds: int = MAX_ROUNDS,
                 max_workers: int = 6):
        self.llm = llm
        self.min_subtopics = min_subtopics
        self.max_rounds = max_rounds
        self.max_workers = max_workers
        self.trace: list[tuple[str, str, str]] = []  # (from, to, label) - every routed message
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ main flow
    def run(self, topic: str, must_cover: tuple[str, ...] = ()) -> ResearchReport:
        """`must_cover`: categories the requester requires in the report (scope requirements)."""
        goal = (f"Produce a broad, balanced overview of '{topic}' that covers every major category, "
                f"with concrete facts, maturity, costs and limitations for each.")

        # Step 2: decomposition covering the full breadth.
        subtopics = self.decompose(topic, must_cover)
        research = {s.name.lower(): SubtopicResearch(s) for s in subtopics}

        # Step 3: first round of delegation (all subtopics in parallel).
        self._delegate(topic, goal, [(r, None) for r in research.values()])
        rounds = 1

        # Steps 4-5: evaluate coverage, re-delegate gaps, repeat.
        while True:
            work = self.evaluate(topic, research)
            log.info("round %d coverage: %s", rounds, ", ".join(f"{r.subtopic.name}={r.status}" for r in research.values()))
            if not work:
                break
            if rounds >= self.max_rounds:
                log.warning("Stopping refinement after %d rounds; %d subtopic(s) still not fully covered.",
                            rounds, len(work))
                break
            rounds += 1
            log.info("round %d: re-delegating %d gap(s): %s", rounds, len(work),
                     ", ".join(r.subtopic.name for r, _ in work))
            self._delegate(topic, goal, work)

        markdown = self.write_report(topic, list(research.values()), rounds)
        return ResearchReport(topic, list(research.values()), rounds, markdown)

    # ------------------------------------------------------------------ 1. decomposition
    def decompose(self, topic: str, must_cover: tuple[str, ...] = ()) -> list[Subtopic]:
        prompt = (
            f"Break the research topic '{topic}' into at least {self.min_subtopics} distinct subtopics "
            f"that TOGETHER cover the full breadth of the subject. Include smaller, emerging and "
            f"experimental categories, not just the best-known ones. Also include borderline or adjacent "
            f"categories that are commonly discussed alongside this topic, even if their classification "
            f"is debated, and say so in their description. Subtopics must not overlap. "
            f"For each, give a one-sentence scope description and a focused search query."
        )
        subtopics: list[Subtopic] = []
        for attempt in range(1, 3):
            data = complete_json(self.llm, COORDINATOR_SYSTEM, prompt, DECOMPOSE_SCHEMA)
            subtopics = _unique([Subtopic(**s) for s in data["subtopics"]])
            uncovered = _uncovered(must_cover, subtopics)
            if len(subtopics) >= self.min_subtopics and not uncovered:
                log.info("decomposed '%s' into %d subtopics: %s", topic, len(subtopics),
                         ", ".join(s.name for s in subtopics))
                return subtopics

            # Too narrow: tell Claude exactly what is wrong and ask again.
            names = ", ".join(s.name for s in subtopics)
            if len(subtopics) < self.min_subtopics:
                log.warning("decomposition attempt %d gave only %d subtopics; asking again", attempt, len(subtopics))
                prompt += (f"\n\nYour previous answer had only {len(subtopics)} subtopics ({names}). That is too "
                           f"narrow: it leaves whole categories un-researched. Give at least {self.min_subtopics}.")
            if uncovered:
                log.warning("decomposition attempt %d does not cover required: %s; asking again",
                            attempt, ", ".join(uncovered))
                prompt += (f"\n\nYour previous answer ({names}) does not cover these required categories: "
                           f"{', '.join(uncovered)}. Add a subtopic for each of them.")

        if len(subtopics) < self.min_subtopics:
            raise RuntimeError(f"Could not decompose '{topic}' into {self.min_subtopics}+ subtopics")
        # Last resort: the coordinator adds the required categories itself.
        for name in _uncovered(must_cover, subtopics):
            log.warning("adding required subtopic the decomposition kept missing: %s", name)
            subtopics.append(Subtopic(name.capitalize(), f"{name} as part of {topic}", f"{name} {topic}"))
        return subtopics

    # ------------------------------------------------------------------ delegation
    def _delegate(self, topic: str, goal: str, work: list[tuple[SubtopicResearch, FollowUp | None]]) -> None:
        with ThreadPoolExecutor(max_workers=self.max_workers) as pool:
            list(pool.map(lambda item: self._research_subtopic(topic, goal, *item), work))

    def _research_subtopic(self, topic: str, goal: str, r: SubtopicResearch, follow_up: FollowUp | None) -> None:
        s = r.subtopic

        # Web search subagent: ALL context is in its prompt (it has no memory of earlier rounds).
        web = self._invoke("web_search", s.name, web_search_agent,
                           topic=topic, research_goal=goal, subtopic=s.name, description=s.description,
                           query=follow_up.query if follow_up else s.search_query,
                           prior_findings=r.findings_text() if follow_up else None,
                           gap=follow_up.gap if follow_up else None)
        if web is not None:
            r.findings.append(("web_search", web))

        # Dynamic selection: only call document analysis when there are relevant documents,
        # and only once - the documents do not change between rounds.
        docs = find_documents(s.name)
        if docs and not r.documents_analysed and web is not None:
            analysis = self._invoke("document_analysis", s.name, document_analysis_agent,
                                    topic=topic, research_goal=goal, subtopic=s.name,
                                    documents=docs, web_findings=web)  # routed via the coordinator
            if analysis is not None:
                r.findings.append(("document_analysis", analysis))
                r.documents_analysed = True

    def _invoke(self, agent: str, label: str, fn, **context) -> str | None:
        """Call one subagent with one uniform error policy: retry once, then record the failure."""
        for attempt in (1, 2):
            self._route("coordinator", agent, label)
            try:
                result = fn(self.llm, **context)
                self._route(agent, "coordinator", label, f"{len(result)} chars")
                return result
            except Exception as exc:  # consistent error handling lives in the hub
                log.warning("%s [%s] failed (attempt %d): %s", agent, label, attempt, exc)
        return None

    def _route(self, sender: str, receiver: str, subtopic: str, note: str = "") -> None:
        with self._lock:
            self.trace.append((sender, receiver, subtopic))
        log.info("%-17s -> %-17s [%s] %s", sender, receiver, subtopic, note)

    # ------------------------------------------------------------------ evaluation
    def evaluate(self, topic: str, research: dict[str, SubtopicResearch]) -> list[tuple[SubtopicResearch, FollowUp | None]]:
        """Aggregate all findings, ask for a coverage assessment, return the follow-up work."""
        aggregated = "\n\n".join(
            f"## {r.subtopic.name}\nScope: {r.subtopic.description}\n{r.findings_text() or '(no findings)'}"
            for r in research.values()
        )
        prompt = (
            f"Research topic: {topic}\n\nFindings collected so far, by subtopic:\n\n{aggregated}\n\n"
            "For EACH subtopic above, rate coverage: 'covered' (substantive facts on what it is, maturity, "
            "costs or scale, and limitations), 'partial' (some of that is missing) or 'missing'. For "
            "partial or missing subtopics, describe the gap and give a targeted follow-up search query. "
            "Then list any important areas of the topic that NO subtopic covers at all (missing_areas); "
            "leave it empty if the subtopics already cover the full breadth."
        )
        data = complete_json(self.llm, COORDINATOR_SYSTEM, prompt, EVALUATE_SCHEMA)

        work: list[tuple[SubtopicResearch, FollowUp | None]] = []
        for a in data["assessments"]:
            r = research.get(a["subtopic"].lower())
            if r is None:
                continue
            r.status, r.gap = a["status"], a["gap"]
            if r.status != "covered":
                work.append((r, FollowUp(a["follow_up_query"] or r.subtopic.search_query, a["gap"])))

        for area in data["missing_areas"]:  # breadth gaps the decomposition missed
            key = area["name"].lower()
            if key in research or len(research) >= MAX_SUBTOPICS:
                continue
            log.info("evaluation found an uncovered area: %s", area["name"])
            r = research[key] = SubtopicResearch(Subtopic(**area))
            work.append((r, None))
        return work

    # ------------------------------------------------------------------ final report
    def write_report(self, topic: str, research: list[SubtopicResearch], rounds: int) -> str:
        aggregated = "\n\n".join(f"## {r.subtopic.name}\n{r.findings_text()}" for r in research)
        prompt = (
            f"Write a research report on '{topic}' in Markdown, using ONLY the findings below. Start with "
            f"a 2-3 sentence overview, then one '## <subtopic>' section per subtopic in the order given, "
            f"then a short '## Conclusion'. Keep each section to one short paragraph plus a few bullets.\n\n"
            f"{aggregated}"
        )
        body = self.llm.complete(COORDINATOR_SYSTEM, prompt)

        covered = sum(1 for r in research if r.status == "covered")
        table = "\n".join(f"| {r.subtopic.name} | {r.status} | {', '.join(sorted({a for a, _ in r.findings}))} |"
                          for r in research)
        return (f"{body}\n\n---\n\n## Coverage evaluation\n\n"
                f"Coverage: {covered}/{len(research)} subtopics covered after {rounds} round(s).\n\n"
                f"| Subtopic | Status | Subagents used |\n|---|---|---|\n{table}\n")


def _uncovered(must_cover: tuple[str, ...], subtopics: list[Subtopic]) -> list[str]:
    """Required categories that no subtopic name or description mentions."""
    text = " ".join(f"{s.name} {s.description}" for s in subtopics).lower()
    return [k for k in must_cover if k.lower() not in text]


def _unique(subtopics: list[Subtopic]) -> list[Subtopic]:
    seen, out = set(), []
    for s in subtopics:
        if s.name.lower() not in seen:
            seen.add(s.name.lower())
            out.append(s)
    return out


REQUIRED_FOR_TEST = ("solar", "wind", "geothermal", "tidal", "biomass", "fusion")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    topic = " ".join(sys.argv[1:]) or "renewable energy technologies"

    is_exercise_test = topic == "renewable energy technologies"
    # Step 6: the exercise's test case - the requester requires these six categories.
    report = Coordinator(ClaudeLLM()).run(topic, must_cover=REQUIRED_FOR_TEST if is_exercise_test else ())
    print("\n" + report.markdown)

    if is_exercise_test:
        text = report.markdown.lower()
        hits = [k for k in REQUIRED_FOR_TEST if k in text]
        missing = [k for k in REQUIRED_FOR_TEST if k not in text]
        print(f"Required categories present: {len(hits)}/{len(REQUIRED_FOR_TEST)}"
              + (f" - MISSING: {', '.join(missing)}" if missing else " - OK"))


if __name__ == "__main__":
    main()
