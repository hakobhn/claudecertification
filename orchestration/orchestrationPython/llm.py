"""Thin wrapper around the Claude Messages API.

Every agent in this system (coordinator and subagents) talks to Claude through
`LLM.complete(system, prompt, schema)`. Each call is a brand-new, single-message
conversation: nothing is remembered between calls. That is exactly the
isolation the lesson describes - an agent only knows what is in its prompt.
"""

from __future__ import annotations

import json
from typing import Protocol

import anthropic

MODEL = "claude-opus-5-5"
MAX_TOKENS = 16000


class LLM(Protocol):
    def complete(self, system: str, prompt: str, schema: dict | None = None) -> str: ...


class ClaudeLLM:
    """Real implementation. Tests use a fake with the same `complete` method."""

    def __init__(self, client: anthropic.Anthropic | None = None, model: str = MODEL):
        self.client = client or anthropic.Anthropic()
        self.model = model

    def complete(self, system: str, prompt: str, schema: dict | None = None) -> str:
        kwargs = dict(
            model=self.model,
            max_tokens=MAX_TOKENS,
            system=system,
            messages=[{"role": "user", "content": prompt}],  # fresh history every call
        )
        if schema is not None:
            # Structured output: the reply is guaranteed to be JSON matching `schema`.
            kwargs["output_config"] = {"format": {"type": "json_schema", "schema": schema}}

        response = self.client.messages.create(**kwargs)

        # Same rule as lesson 1.1: stop_reason decides whether the answer is complete.
        if response.stop_reason != "end_turn":
            raise RuntimeError(f"Claude did not finish (stop_reason={response.stop_reason!r})")
        return "".join(b.text for b in response.content if b.type == "text")


def complete_json(llm: LLM, system: str, prompt: str, schema: dict) -> dict:
    return json.loads(llm.complete(system, prompt, schema))
