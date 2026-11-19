"""Thin wrapper around the Claude Messages API (same as lesson 1.2).

Every review pass is one brand-new, single-message call. Tests use a fake.
"""

from __future__ import annotations

import json
from typing import Protocol

import anthropic

MODEL = "claude-opus-5-5"
MAX_TOKENS = 16000


class LLM(Protocol):
    def complete(self, system: str, prompt: str, schema: dict) -> str: ...


class ClaudeLLM:
    def __init__(self, client: anthropic.Anthropic | None = None):
        self.client = client or anthropic.Anthropic()

    def complete(self, system: str, prompt: str, schema: dict) -> str:
        response = self.client.messages.create(
            model=MODEL, max_tokens=MAX_TOKENS, system=system,
            messages=[{"role": "user", "content": prompt}],
            output_config={"format": {"type": "json_schema", "schema": schema}},  # structured output
        )
        if response.stop_reason != "end_turn":
            raise RuntimeError(f"Claude did not finish (stop_reason={response.stop_reason!r})")
        return "".join(b.text for b in response.content if b.type == "text")


def complete_json(llm: LLM, system: str, prompt: str, schema: dict) -> dict:
    return json.loads(llm.complete(system, prompt, schema))
