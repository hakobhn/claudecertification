"""The three subagents, their tools, and the agent loop every agent runs.

Each subagent is described by an AgentDefinition, the same three parts the Agent SDK uses:
  1. a description   - the coordinator reads it to decide when to spawn the agent
  2. a system prompt - the goal and quality criteria (not a step-by-step procedure)
  3. tools           - restricted to the agent's role

Mock data: the web index and the internal documents below are made up for the exercise.
"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from typing import Callable

MODEL = "claude-opus-5-5"
MAX_TOKENS = 16000
MAX_ITERATIONS = 20  # safety cap per agent - NOT the stopping mechanism (stop_reason is)

log = logging.getLogger("agents")

# --------------------------------------------------------------------------- structured findings (step 3)


def _nullable(type_: str) -> dict:
    return {"anyOf": [{"type": type_}, {"type": "null"}]}


@dataclass(frozen=True)
class Finding:
    """One research result. Content is kept apart from the metadata that says where it came from."""
    # content
    claim: str
    analysis: str
    # metadata (attribution)
    source_url: str | None
    source_title: str | None
    document_name: str | None
    page_number: int | None
    confidence: str      # high / medium / low
    retrieved_by: str    # which subagent found it

    @staticmethod
    def from_dict(d: dict) -> "Finding":
        return Finding(**{k: d.get(k) for k in Finding.__dataclass_fields__})

    def to_dict(self) -> dict:
        return asdict(self)

    def content_only(self) -> dict:
        """What is left when metadata gets stripped - the bug the exam asks about."""
        return {"claim": self.claim, "analysis": self.analysis}


# The same Finding as a JSON Schema, so each research subagent's final message is a list of Findings.
FINDING_SCHEMA = {
    "type": "object",
    "properties": {
        "claim": {"type": "string"},
        "analysis": {"type": "string"},
        "source_url": _nullable("string"),
        "source_title": _nullable("string"),
        "document_name": _nullable("string"),
        "page_number": _nullable("integer"),
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
        "retrieved_by": {"type": "string"},
    },
    "required": ["claim", "analysis", "source_url", "source_title", "document_name", "page_number",
                 "confidence", "retrieved_by"],
    "additionalProperties": False,
}

FINDINGS_SCHEMA = {
    "type": "object",
    "properties": {"findings": {"type": "array", "items": FINDING_SCHEMA}},
    "required": ["findings"],
    "additionalProperties": False,
}

SYNTHESIS_SCHEMA = {
    "type": "object",
    "properties": {
        "report": {"type": "string"},
        "claims": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "claim": {"type": "string"},
                "source_url": _nullable("string"),
                "document_name": _nullable("string"),
                "page_number": _nullable("integer"),
            },
            "required": ["claim", "source_url", "document_name", "page_number"],
            "additionalProperties": False,
        }},
    },
    "required": ["report", "claims"],
    "additionalProperties": False,
}

# --------------------------------------------------------------------------- mock sources

WEB_INDEX = [
    {"url": "https://example.org/offshore-wind-outlook-2025", "title": "Offshore Wind Outlook 2025",
     "keywords": ["capacity", "growth", "outlook", "offshore", "wind"],
     "snippet": "Global offshore wind capacity reached about 83 GW at the end of 2024, with China and Europe "
                "accounting for most new installations."},
    {"url": "https://example.org/offshore-wind-lcoe", "title": "The falling cost of offshore wind",
     "keywords": ["cost", "lcoe", "price", "auction", "trend"],
     "snippet": "The levelised cost of fixed-bottom offshore wind fell roughly 60% between 2012 and 2023, but "
                "auction prices rose again in 2023-2024 as interest rates and turbine prices increased."},
    {"url": "https://example.org/floating-wind-pilots", "title": "Floating offshore wind: pilots to projects",
     "keywords": ["floating", "deep", "pilot", "risk"],
     "snippet": "Floating foundations open deep-water sites, but floating projects still cost about twice as "
                "much per MWh as fixed-bottom ones."},
    {"url": "https://example.org/offshore-supply-chain", "title": "Supply-chain strain in offshore wind",
     "keywords": ["supply", "vessel", "risk", "turbine", "operational"],
     "snippet": "Shortages of installation vessels and large turbine components delayed several 2024 projects."},
]

DOCUMENTS = {
    "Offshore Wind Maintenance Report": {
        1: "Turbine availability across the North Sea cluster was 96.1% this year.",
        2: "Gearbox failures caused 40% of downtime; the 15 MW direct-drive units had none.",
        3: "Jack-up vessel day rates rose 35%, which makes operations and maintenance the largest "
           "controllable cost after financing.",
    },
    "North Sea Cluster Investment Memo": {
        1: "Financing costs now make up about half of lifetime cost for new offshore sites.",
        2: "The board approved a 1.2 GW extension, subject to a contract-for-difference strike price "
           "above 80 EUR/MWh.",
    },
}

# --------------------------------------------------------------------------- tools


def web_search(query: str) -> str:
    """Mock search: every result carries its URL and title, so the agent can cite them."""
    words = {w.strip("?,.").lower() for w in query.split()}
    hits = [{"url": r["url"], "title": r["title"], "snippet": r["snippet"]}
            for r in WEB_INDEX if words & set(r["keywords"])]
    return json.dumps({"query": query, "results": hits})


def list_documents() -> str:
    return json.dumps([{"document_name": name, "pages": len(pages)} for name, pages in DOCUMENTS.items()])


def read_document(document_name: str) -> str:
    """Returns every page with its page number, so the agent can cite pages."""
    if document_name not in DOCUMENTS:
        raise ValueError(f"no document named {document_name!r}")
    pages = [{"page_number": n, "text": t} for n, t in DOCUMENTS[document_name].items()]
    return json.dumps({"document_name": document_name, "pages": pages})


TOOL_DEFINITIONS = {
    "web_search": {
        "name": "web_search",
        "description": "Search public web sources. Returns results with url, title and snippet.",
        "input_schema": {"type": "object", "properties": {"query": {"type": "string"}},
                         "required": ["query"], "additionalProperties": False},
    },
    "list_documents": {
        "name": "list_documents",
        "description": "List the internal documents and how many pages each has.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    "read_document": {
        "name": "read_document",
        "description": "Read an internal document. Returns every page with its page_number.",
        "input_schema": {"type": "object", "properties": {"document_name": {"type": "string"}},
                         "required": ["document_name"], "additionalProperties": False},
    },
}

TOOL_FUNCTIONS: dict[str, Callable[..., str]] = {
    "web_search": web_search,
    "list_documents": list_documents,
    "read_document": read_document,
}

# --------------------------------------------------------------------------- agent definitions (step 2)


@dataclass(frozen=True)
class AgentDefinition:
    name: str
    description: str
    system_prompt: str
    tools: tuple[str, ...]
    output_schema: dict


# Every finding must carry these fields. Only the subagent's FINAL message reaches the coordinator -
# its tool calls and results do not - so the prompt must say which fields to return.
_RETURN_FIELDS = ("For EVERY finding return: claim, analysis, source_url, source_title, document_name, "
                  "page_number, confidence (high/medium/low) and retrieved_by. A finding without its source "
                  "is useless downstream.")

WEB_RESEARCH = AgentDefinition(
    name="web_research",
    description="Researches public web sources. Returns findings with the source URL and title of each.",
    system_prompt=("You are a web research subagent. Goal: find concrete, current facts on the question you are "
                   "given, from public sources only. Quality bar: every finding is backed by one search result "
                   "and cites that result's exact url and title; never invent a source. Set document_name and "
                   "page_number to null and retrieved_by to 'web_research'. " + _RETURN_FIELDS),
    tools=("web_search",),
    output_schema=FINDINGS_SCHEMA,
)

DOCUMENT_ANALYSIS = AgentDefinition(
    name="document_analysis",
    description="Analyses the internal documents. Returns findings with the document name and page number.",
    system_prompt=("You are a document analysis subagent. Goal: extract the facts in the internal documents that "
                   "answer the question you are given. Quality bar: every finding cites the exact document_name "
                   "and page_number it came from; do not add facts that are not in the documents. Set source_url "
                   "and source_title to null and retrieved_by to 'document_analysis'. " + _RETURN_FIELDS),
    tools=("list_documents", "read_document"),
    output_schema=FINDINGS_SCHEMA,
)

SYNTHESIS = AgentDefinition(
    name="synthesis",
    description="Writes the final report from the findings of the research subagents. Has no tools.",
    system_prompt=("You are a synthesis subagent. Goal: write a short, balanced report that answers the question "
                   "using ONLY the findings you are given. Quality bar: every factual claim cites its source - the "
                   "finding's source_url, or its document_name and page_number - copied exactly. If two findings "
                   "disagree, say so. Return the report text plus the list of claims with their citations."),
    tools=(),
    output_schema=SYNTHESIS_SCHEMA,
)

AGENTS = {a.name: a for a in (WEB_RESEARCH, DOCUMENT_ANALYSIS, SYNTHESIS)}

# --------------------------------------------------------------------------- the agent loop (lesson 1.1)

ToolRunner = Callable[[list], list[dict]]  # tool_use blocks of one response -> tool_result blocks


def run_agent(client, *, label: str, system: str, prompt: str, tools: list[dict],
              run_tools: ToolRunner, output_schema: dict | None = None) -> str:
    """One agent, one fresh conversation. stop_reason alone decides when it is done."""
    messages: list[dict] = [{"role": "user", "content": prompt}]  # nothing inherited: only this prompt
    for iteration in range(1, MAX_ITERATIONS + 1):  # safety cap only
        kwargs = dict(model=MODEL, max_tokens=MAX_TOKENS, system=system, messages=messages)
        if tools:
            kwargs["tools"] = tools
        if output_schema is not None:
            kwargs["output_config"] = {"format": {"type": "json_schema", "schema": output_schema}}
        response = client.messages.create(**kwargs)
        log.info("%-17s iteration %d: stop_reason=%s", label, iteration, response.stop_reason)

        if response.stop_reason == "end_turn":
            return "".join(b.text for b in response.content if b.type == "text")
        if response.stop_reason == "tool_use":
            messages.append({"role": "assistant", "content": response.content})
            tool_uses = [b for b in response.content if b.type == "tool_use"]
            messages.append({"role": "user", "content": run_tools(tool_uses)})  # all results, ONE message
            continue
        raise RuntimeError(f"{label} did not finish (stop_reason={response.stop_reason!r})")

    raise RuntimeError(f"{label} stopped: safety iteration cap reached")


def restricted_tool_runner(definition: AgentDefinition) -> ToolRunner:
    """Runs only the tools this agent is allowed; anything else comes back as an error result."""

    def run(tool_uses: list) -> list[dict]:
        results = []
        for block in tool_uses:
            result = {"type": "tool_result", "tool_use_id": block.id}
            if block.name not in definition.tools:
                result.update(content=f"Error: tool '{block.name}' is not allowed for {definition.name}",
                              is_error=True)
            else:
                try:
                    result["content"] = TOOL_FUNCTIONS[block.name](**block.input)
                except Exception as e:  # errors go back to Claude, never crash the loop
                    result.update(content=f"Error: {type(e).__name__}: {e}", is_error=True)
            results.append(result)
        return results

    return run
