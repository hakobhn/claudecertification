"""The two spokes: a web search subagent and a document analysis subagent.

ISOLATION: a subagent call is a fresh conversation. It does NOT get the
coordinator's system prompt, the coordinator's history, other subagents'
results, or anything from its own earlier invocations. Every fact it needs is
written into its prompt by the coordinator - see the `build_*_prompt` functions.

PARTITIONING: each subagent covers a different source type (public web vs.
internal documents), so no two subagents duplicate the same work.
"""

from __future__ import annotations

from documents import Document
from llm import LLM

WEB_SEARCH_SYSTEM = (
    "You are a web research subagent. You research ONE assigned subtopic using public sources: "
    "news, industry reports, government and academic publications. You have no live search tool, "
    "so answer from your own knowledge and say when a figure may be out of date. "
    "Stay strictly inside your assigned subtopic. Reply with 5-8 concise bullet points of concrete "
    "facts: how the technology works, maturity, typical costs or capacity figures, recent "
    "developments, and main limitations."
)

DOCUMENT_ANALYSIS_SYSTEM = (
    "You are a document analysis subagent. You analyse ONLY the internal documents given to you "
    "for ONE assigned subtopic. Extract the key facts and figures, and say where the documents agree "
    "or disagree with the public findings you are given. Do not invent facts that are not in the "
    "documents. Reply with 3-6 concise bullet points."
)


def build_web_search_prompt(
    *,
    topic: str,
    research_goal: str,
    subtopic: str,
    description: str,
    query: str,
    prior_findings: str | None = None,
    gap: str | None = None,
) -> str:
    parts = [
        f"Overall research topic: {topic}",
        f"Research goal: {research_goal}",
        f"Your assigned subtopic: {subtopic}",
        f"Scope of this subtopic: {description}",
        f"Search focus: {query}",
    ]
    if prior_findings:
        # Refinement round: the coordinator passes what is already known, because the
        # subagent has no memory of its earlier invocation.
        parts.append(f"Findings already collected for this subtopic (do not repeat them):\n{prior_findings}")
    if gap:
        parts.append(f"Gap the coordinator wants filled: {gap}")
    return "\n\n".join(parts)


def build_document_analysis_prompt(
    *,
    topic: str,
    research_goal: str,
    subtopic: str,
    documents: list[Document],
    web_findings: str,
) -> str:
    docs = "\n\n".join(f"### {d.title}\n{d.text}" for d in documents)
    return "\n\n".join([
        f"Overall research topic: {topic}",
        f"Research goal: {research_goal}",
        f"Your assigned subtopic: {subtopic}",
        f"Internal documents to analyse:\n{docs}",
        # Context from another subagent - routed THROUGH the coordinator, never directly.
        f"Public findings from the web search subagent (passed on by the coordinator):\n{web_findings}",
    ])


def web_search_agent(llm: LLM, **context) -> str:
    return llm.complete(WEB_SEARCH_SYSTEM, build_web_search_prompt(**context))


def document_analysis_agent(llm: LLM, **context) -> str:
    return llm.complete(DOCUMENT_ANALYSIS_SYSTEM, build_document_analysis_prompt(**context))
