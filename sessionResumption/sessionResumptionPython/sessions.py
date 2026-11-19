"""The three session management options from lesson 1.7.

A session is a named conversation history saved to sessions/<name>.json. The history holds
everything: the agent's reasoning AND every tool result (e.g. the contents of each file it read).

    resume(name)         continue the SAME history         -> old tool results come back too
    fork(name, new)      copy the history into a new branch -> original untouched; branches independent
    start(name) + summary_injection_prompt(...)
                         a NEW, empty history whose first message is a curated summary
                         -> prior knowledge kept, stale tool results gone

Claude Code equivalents: `claude --name x`, `claude --resume x`, `--resume x --fork-session`.
Agent SDK: `resume=<id>` and `fork_session=True`.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

SESSIONS_DIR = Path(__file__).parent / "sessions"


@dataclass
class Session:
    name: str
    messages: list[dict] = field(default_factory=list)  # [{"role": ..., "content": [blocks]}]

    def save(self) -> None:
        SESSIONS_DIR.mkdir(exist_ok=True)
        (SESSIONS_DIR / f"{self.name}.json").write_text(json.dumps(self.messages, indent=1), encoding="utf-8")

    def tool_results(self) -> list[str]:
        """Every tool result in the history: what the agent will reason from if this session continues."""
        return [b["content"] for m in self.messages for b in m["content"] if b["type"] == "tool_result"]


def start(name: str) -> Session:
    """A new, empty, named session (claude --name <name>)."""
    return Session(name)


def resume(name: str) -> Session:
    """Continue a named session (claude --resume <name>): the ENTIRE history is restored."""
    path = SESSIONS_DIR / f"{name}.json"
    return Session(name, json.loads(path.read_text(encoding="utf-8")))


def fork(name: str, new_name: str) -> Session:
    """Branch a session (fork_session=True): a copy of the history under a new name.
    The original is not changed, and neither branch will see what the other does next."""
    branch = Session(new_name, resume(name).messages)
    branch.save()
    return branch


def summary_injection_prompt(summary: dict, changed_files: list[str], question: str) -> str:
    """The first message of a fresh session: curated conclusions, no raw tool output."""
    return (f"You are continuing a code review from a previous session. Its structured summary:\n\n"
            f"{json.dumps(summary, indent=1)}\n\n"
            f"Since then these {len(changed_files)} files were changed: {', '.join(changed_files)}.\n"
            "Re-read ONLY those files to verify the fixes and check for new issues. For every other file, "
            "trust the summary and do not re-read it.\n\n" + question)
