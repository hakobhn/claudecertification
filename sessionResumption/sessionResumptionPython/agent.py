"""The review agent: lesson 1.1's loop, with two read-only tools over a workspace folder.

run_turn() adds one user message to a session, loops until end_turn, and saves the session.
It returns the structured answer and the files the agent read during this turn.
"""

from __future__ import annotations

import json
from pathlib import Path

from sessions import Session

MODEL = "claude-opus-5-5"
SYSTEM = ("You review the room booking service of a hotel chain (Node.js). The code is in the workspace: "
          "use list_files and read_file. Cite file names and line numbers.")

TOOLS = [
    {"name": "list_files", "description": "List the source files in the workspace.", "strict": True,
     "input_schema": {"type": "object", "properties": {}, "required": [], "additionalProperties": False}},
    {"name": "read_file", "description": "Read one source file, with line numbers.", "strict": True,
     "input_schema": {"type": "object", "properties": {"path": {"type": "string", "description": "e.g. auth.js"}},
                      "required": ["path"], "additionalProperties": False}},
]


def run_tool(name: str, args: dict, workspace: Path) -> str:
    if name == "list_files":
        return "\n".join(sorted(p.name for p in workspace.glob("*.js")))
    if name == "read_file":
        path = workspace / Path(args["path"]).name  # stay inside the workspace
        if not path.exists():
            return f"No such file: {args['path']}"
        return "\n".join(f"{i:>3}| {line}" for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1))
    return f"Unknown tool {name}"


def blocks(content) -> list[dict]:
    """API response blocks -> plain dicts, so the history can be saved as JSON."""
    out = []
    for b in content:
        if b.type == "text":
            out.append({"type": "text", "text": b.text})
        elif b.type == "tool_use":
            out.append({"type": "tool_use", "id": b.id, "name": b.name, "input": b.input})
    return out


def run_turn(session: Session, prompt: str, schema: dict, client, workspace: Path) -> tuple[dict, list[str]]:
    session.messages.append({"role": "user", "content": [{"type": "text", "text": prompt}]})
    files_read = []
    for _ in range(30):  # safety cap only
        response = client.messages.create(
            model=MODEL, max_tokens=16000, system=SYSTEM, tools=TOOLS, messages=session.messages,
            output_config={"format": {"type": "json_schema", "schema": schema}})
        session.messages.append({"role": "assistant", "content": blocks(response.content)})

        if response.stop_reason == "end_turn":
            session.save()
            text = "".join(b.text for b in response.content if b.type == "text")
            return json.loads(text), files_read
        if response.stop_reason != "tool_use":
            raise RuntimeError(f"stop_reason={response.stop_reason}")

        results = []
        for b in response.content:
            if b.type == "tool_use":
                if b.name == "read_file":
                    files_read.append(Path(b.input["path"]).name)
                results.append({"type": "tool_result", "tool_use_id": b.id,
                                "content": run_tool(b.name, b.input, workspace)})
        session.messages.append({"role": "user", "content": results})
    raise RuntimeError("safety cap reached")
