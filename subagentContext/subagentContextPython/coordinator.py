"""Build Exercise 1.3 - Subagent invocation and context passing.

The six steps of the exercise, and where they are:
  1. Coordinator with `Task` in allowed_tools, subagents in options.agents  -> CoordinatorOptions
  2. Two research subagents, each an AgentDefinition with restricted tools   -> agents.py
  3. A structured Finding: content separated from source metadata          -> agents.Finding
  4. COMPLETE findings, metadata intact, passed to the synthesis subagent    -> Coordinator._spawn
  5. Verify every claim in the report cites a real source                    -> verify_attribution
  6. Independent subagents spawned in parallel (several Task calls, ONE turn) -> Coordinator._run_tasks
"""

from __future__ import annotations

import argparse
import json
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

import anthropic

import agents as a
from agents import AgentDefinition, Finding

log = logging.getLogger("coordinator")

COORDINATOR_SYSTEM = (
    "You coordinate a team of subagents and never research anything yourself. Use the Task tool to spawn them. "
    "Subagents whose work is independent must be spawned in the SAME response (several Task calls at once) so "
    "they run in parallel. Each subagent sees nothing but the prompt you write, so state the question, the goal "
    "and the quality criteria - not step-by-step instructions. When the research is back, spawn the synthesis "
    "subagent; the complete research findings are attached to its prompt for you. When synthesis is done, "
    "reply with one sentence saying the report is ready.")


# --------------------------------------------------------------------------- step 1: the coordinator's options


@dataclass
class CoordinatorOptions:
    """Same shape as the Agent SDK's query() options: allowedTools + agents."""
    allowed_tools: list[str] = field(default_factory=lambda: ["Task"])  # without "Task" nothing can be spawned
    agents: dict[str, AgentDefinition] = field(default_factory=lambda: dict(a.AGENTS))
    system_prompt: str = COORDINATOR_SYSTEM


def task_tool(agents: dict[str, AgentDefinition]) -> dict:
    """The Task tool. Its description lists each subagent's description - that is how the coordinator picks one."""
    return {
        "name": "Task",
        "description": "Spawn a subagent to do part of the work. It starts with an empty context: it sees ONLY "
                       "the prompt you write. Available subagents:\n"
                       + "\n".join(f"- {d.name}: {d.description}" for d in agents.values()),
        "input_schema": {
            "type": "object",
            "properties": {
                "subagent_type": {"type": "string", "enum": list(agents)},
                "description": {"type": "string", "description": "3-5 word label for the task"},
                "prompt": {"type": "string",
                           "description": "The question, the goal and the quality criteria for this subagent."},
            },
            "required": ["subagent_type", "description", "prompt"],
            "additionalProperties": False,
        },
    }


@dataclass
class Result:
    report: str
    claims: list[dict]
    findings: list[Finding]
    unattributed: list[dict]        # step 5: claims whose source is not among the findings
    spawn_batches: list[list[str]]  # step 6: which subagents each coordinator response spawned


class Coordinator:
    def __init__(self, client=None, options: CoordinatorOptions | None = None, strip_metadata: bool = False):
        self.client = client or anthropic.Anthropic()
        self.options = options or CoordinatorOptions()
        self.strip_metadata = strip_metadata  # True reproduces the exam's bug: metadata dropped on the way
        self._lock = threading.Lock()

    def run(self, question: str) -> Result:
        self.findings: list[Finding] = []
        self.synthesis: dict | None = None
        self.spawn_batches: list[list[str]] = []

        # Step 1: the coordinator can spawn subagents only if Task is in its allowed tools.
        tools = [task_tool(self.options.agents)] if "Task" in self.options.allowed_tools else []
        if not tools:
            log.warning("'Task' is not in allowed_tools: the coordinator cannot spawn any subagent")
        a.run_agent(self.client, label="coordinator", system=self.options.system_prompt, prompt=question,
                    tools=tools, run_tools=self._run_tasks)

        synthesis = self.synthesis or {"report": "", "claims": []}
        return Result(synthesis["report"], synthesis["claims"], self.findings,
                      verify_attribution(synthesis["claims"], self.findings), self.spawn_batches)

    # ----------------------------------------------------------------------- step 6: parallel spawning

    def _run_tasks(self, tool_uses: list) -> list[dict]:
        """All Task calls from ONE coordinator response run at the same time; it waits for all of them."""
        self.spawn_batches.append([b.input.get("subagent_type") for b in tool_uses])
        log.info("spawning in parallel: %s", ", ".join(map(str, self.spawn_batches[-1])))
        with ThreadPoolExecutor(max_workers=len(tool_uses)) as pool:
            outputs = list(pool.map(self._spawn, tool_uses))
        return [{"type": "tool_result", "tool_use_id": b.id, "content": out, **({"is_error": True} if err else {})}
                for b, (out, err) in zip(tool_uses, outputs)]

    # ----------------------------------------------------------------------- invocation + step 4: context passing

    def _spawn(self, block) -> tuple[str, bool]:
        definition = self.options.agents.get(block.input.get("subagent_type"))
        if block.name != "Task" or definition is None:
            return f"Error: cannot run {block.name}({json.dumps(block.input)})", True

        # Isolation: the subagent starts from this prompt only - no coordinator history, no other results.
        prompt = block.input["prompt"]
        if definition is a.SYNTHESIS:
            prompt += ("\n\nFindings from the research subagents (complete, with source metadata):\n"
                       + json.dumps(self._findings_to_pass(), indent=2))

        try:
            output = a.run_agent(self.client, label=definition.name, system=definition.system_prompt,
                                 prompt=prompt, tools=[a.TOOL_DEFINITIONS[t] for t in definition.tools],
                                 run_tools=a.restricted_tool_runner(definition),
                                 output_schema=definition.output_schema)
            data = json.loads(output)
        except Exception as e:
            log.warning("%s failed: %s", definition.name, e)
            return f"Error: {definition.name} failed: {e}", True

        with self._lock:
            if definition is a.SYNTHESIS:
                self.synthesis = data
            else:
                self.findings.extend(Finding.from_dict(f) for f in data["findings"])
        return output, False  # only the subagent's final message goes back to the coordinator

    def _findings_to_pass(self) -> list[dict]:
        if self.strip_metadata:  # the bug: content passed on, attribution lost
            return [f.content_only() for f in self.findings]
        return [f.to_dict() for f in self.findings]


# --------------------------------------------------------------------------- step 5: verify attribution


def verify_attribution(claims: list[dict], findings: list[Finding]) -> list[dict]:
    """Returns the claims that do NOT cite a URL, or a document page, that some finding came from."""
    urls = {f.source_url for f in findings if f.source_url}
    pages = {(f.document_name, f.page_number) for f in findings if f.document_name}
    return [c for c in claims
            if c.get("source_url") not in urls and (c.get("document_name"), c.get("page_number")) not in pages]


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    parser = argparse.ArgumentParser(description="Build Exercise 1.3 - subagent invocation and context passing")
    parser.add_argument("question", nargs="*")
    parser.add_argument("--strip-metadata", action="store_true",
                        help="reproduce the bug: pass findings to synthesis without their source metadata")
    args = parser.parse_args()
    question = " ".join(args.question) or "What are the cost trends and main operational risks of offshore wind?"

    result = Coordinator(strip_metadata=args.strip_metadata).run(question)
    print("\n" + result.report + "\n")
    print(f"Spawn batches: {result.spawn_batches}")
    print(f"Findings: {len(result.findings)}, claims: {len(result.claims)}, "
          f"unattributed claims: {len(result.unattributed)}")
    for claim in result.unattributed:
        print(f"  NO SOURCE: {claim['claim']}")


if __name__ == "__main__":
    main()
