"""The two review architectures from lesson 1.6. Same model, same system prompt.

    SINGLE PASS                          MULTI-PASS (a fixed pipeline)
    ┌──────────────────────┐             file 1 ─► local pass ─┐
    │ all 14 files in ONE  │             file 2 ─► local pass ─┤
    │ prompt               │             ...                   ├─► integration pass ─► report
    └──────────────────────┘             file 14 ─► local pass ─┘    (cross-file only)

The multi-pass review is prompt chaining: the steps are known in advance and never change
based on what they find. That suits a code review, whose steps are predictable.
"""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from llm import LLM, complete_json

SYSTEM = ("You are a senior code reviewer for an online bookshop's Node.js checkout code. "
          "Report real defects: bugs, security holes, data-handling errors and questionable patterns. "
          "Cite the exact line number from the numbered listing.")


# ---------------------------------------------------------------- JSON schemas (structured output)
def obj(**props) -> dict:
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


STR = {"type": "string"}
SEVERITY = {"type": "string", "enum": ["critical", "high", "medium", "low"]}
FINDING = obj(line={"type": "integer"}, severity=SEVERITY, description=STR)
CROSS_FILE = obj(files={"type": "array", "items": STR}, severity=SEVERITY, description=STR)

SINGLE_PASS_SCHEMA = obj(
    files={"type": "array", "items": obj(file=STR, findings={"type": "array", "items": FINDING})},
    cross_file_issues={"type": "array", "items": CROSS_FILE})

PER_FILE_SCHEMA = obj(
    findings={"type": "array", "items": FINDING},
    interface={"type": "array", "items": STR,
               "description": "Every export AND every import, with parameters, return value and units."})

INTEGRATION_SCHEMA = obj(cross_file_issues={"type": "array", "items": CROSS_FILE})


# ---------------------------------------------------------------- input
def load_files(directory: str | Path) -> dict[str, str]:
    """Reads every .js/.ts file in the directory, in name order."""
    paths = sorted(p for p in Path(directory).iterdir() if p.suffix in (".js", ".ts"))
    if len(paths) < 10:
        raise ValueError(f"need at least 10 source files to show attention dilution, found {len(paths)}")
    return {p.name: p.read_text(encoding="utf-8") for p in paths}


def numbered(name: str, source: str) -> str:
    lines = "\n".join(f"{i:>3}| {line}" for i, line in enumerate(source.splitlines(), 1))
    return f"=== {name} ===\n{lines}"


# ---------------------------------------------------------------- architecture 1: one pass
def single_pass(files: dict[str, str], llm: LLM) -> dict:
    """Every file in one prompt. The baseline that shows attention dilution."""
    prompt = (f"Review this pull request. It contains {len(files)} files. Give findings for every file, "
              "then any cross-file issues.\n\n" + "\n\n".join(numbered(n, s) for n, s in files.items()))
    return complete_json(llm, SYSTEM, prompt, SINGLE_PASS_SCHEMA)


# ---------------------------------------------------------------- architecture 2: multi-pass
def local_pass(name: str, source: str, llm: LLM) -> dict:
    """Layer 1: one file gets the model's whole attention."""
    prompt = ("Review this ONE file from a pull request. Report its local defects. Also list its interface: "
              "every export and every import, with parameters, return value and units.\n\n"
              + numbered(name, source))
    return {"file": name, **complete_json(llm, SYSTEM, prompt, PER_FILE_SCHEMA)}


def integration_pass(local_results: list[dict], llm: LLM) -> list[dict]:
    """Layer 2: sees the per-file summaries (not the code) and looks only BETWEEN files."""
    prompt = ("The per-file reviews of a pull request are done; their results are below. Do NOT repeat local "
              "issues. Find cross-file issues only:\n"
              "- data flow: a value produced in one file and used in another with different units, types or nullability\n"
              "- API consistency: imports that do not match what the other file exports\n"
              "- pattern consistency: the same code judged differently in different files\n\n"
              + json.dumps(local_results, indent=1))
    return complete_json(llm, SYSTEM, prompt, INTEGRATION_SCHEMA)["cross_file_issues"]


def multi_pass(files: dict[str, str], llm: LLM, workers: int = 7) -> dict:
    with ThreadPoolExecutor(max_workers=workers) as pool:  # local passes are independent
        local = list(pool.map(lambda item: local_pass(*item, llm), files.items()))
    return {"files": [{"file": r["file"], "findings": r["findings"]} for r in local],
            "cross_file_issues": integration_pass(local, llm)}
