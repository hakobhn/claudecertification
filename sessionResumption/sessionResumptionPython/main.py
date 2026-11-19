"""Build Exercise 1.7 - session management: resume vs fork vs fresh start with summary injection.

    python main.py analyse   # day 1: named session reviews 10 files -> sessions/booking-review.json + summary.json
    python main.py fork      # two independent branches from day 1, comparing two refactoring strategies
    python main.py modify    # overnight: the developer fixes the 3 core files
    python main.py resume    # day 2, the trap: resume the session -> stale tool results
    python main.py fresh     # day 2, the fix: fresh session + summary + targeted re-analysis
    python main.py           # all of the above, then the comparison
"""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import anthropic

import sessions
from agent import run_turn

HERE = Path(__file__).parent
CODEBASE = HERE.parent / "codebase"
WORKSPACE = HERE / "workspace"
SUMMARY = HERE / "sessions" / "summary.json"

NAME = "booking-review"
CHANGED = ["auth.js", "database.js", "api-routes.js"]  # the 3 core files fixed overnight
STILL_OPEN = ["bookings.js", "pricing.js"]             # bugs nobody fixed
FIXED_BUGS = {"auth.js": 16, "database.js": 17, "api-routes.js": 25}  # the 3 bugs fixed overnight: file -> line

ISSUE = {"type": "object", "additionalProperties": False, "required": ["line", "severity", "description", "recommendation"],
         "properties": {"line": {"type": "integer"}, "severity": {"type": "string", "enum": ["high", "medium", "low"]},
                        "description": {"type": "string"}, "recommendation": {"type": "string"}}}
SUMMARY_SCHEMA = {"type": "object", "additionalProperties": False, "required": ["files"], "properties": {
    "files": {"type": "array", "items": {
        "type": "object", "additionalProperties": False, "required": ["file", "issues"],
        "properties": {"file": {"type": "string"}, "issues": {"type": "array", "items": ISSUE}}}}}}
STATUS_SCHEMA = {"type": "object", "additionalProperties": False, "required": ["open_bugs", "verified_fixed", "fix_first"],
                 "properties": {
                     "open_bugs": {"type": "array", "items": {
                         "type": "object", "additionalProperties": False, "required": ["file", "line", "description"],
                         "properties": {"file": {"type": "string"}, "line": {"type": "integer"},
                                        "description": {"type": "string"}}}},
                     "verified_fixed": {"type": "array", "items": {"type": "string"}},
                     "fix_first": {"type": "string"}}}

QUESTION = "Which bugs are still open, and which one should I fix first?"


def reset_workspace() -> None:
    shutil.rmtree(WORKSPACE, ignore_errors=True)
    shutil.copytree(CODEBASE / "original", WORKSPACE)


def apply_fixes() -> list[str]:
    for f in CHANGED:
        shutil.copy(CODEBASE / "fixes" / f, WORKSPACE / f)
    return CHANGED


def is_stale(bug: dict) -> bool:
    """Reports one of the 3 bugs that were fixed overnight as still open."""
    line = FIXED_BUGS.get(Path(bug["file"]).name)
    return line is not None and abs(bug["line"] - line) <= 2


def classify(status: dict) -> dict:
    """How the day-2 answer compares with reality."""
    files = [Path(b["file"]).name for b in status["open_bugs"]]
    return {"stale": [f"{b['file']}:{b['line']}" for b in status["open_bugs"] if is_stale(b)],
            "kept": sorted({f for f in files if f in STILL_OPEN}),  # real open bugs, carried over
            "verified_fixed": len(status["verified_fixed"])}


# ---------------------------------------------------------------- the steps
def analyse(client) -> None:
    reset_workspace()
    session = sessions.start(NAME)
    summary, read = run_turn(session, "Review the booking service: read every file and find every bug. Your answer is "
                             "a structured summary that a later session will rely on, so be complete but concise.",
                             SUMMARY_SCHEMA, client, WORKSPACE)
    SUMMARY.write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(f"Session '{NAME}' read {len(read)} files, saved {len(session.messages)} messages "
          f"({len(session.tool_results())} tool results).")
    for f in summary["files"]:
        for i in f["issues"]:
            print(f"  {f['file']}:{i['line']} [{i['severity']}] {i['description']}")


def fork_demo(client) -> None:
    original = len(sessions.resume(NAME).messages)
    schema = {"type": "object", "additionalProperties": False, "required": ["plan"],
              "properties": {"plan": {"type": "array", "items": {"type": "string"}}}}
    for branch, strategy in [("refactor-repository", "a repository layer that owns all SQL"),
                             ("refactor-middleware", "Express middleware for authentication and ownership checks")]:
        session = sessions.fork(NAME, branch)
        answer, _ = run_turn(session, f"Plan a refactor built around {strategy}. Three steps at most.",
                             schema, client, WORKSPACE)
        print(f"\nFork '{branch}' ({len(session.messages)} messages):")
        for step in answer["plan"]:
            print(f"  - {step}")
    print(f"\nOriginal '{NAME}' still has {len(sessions.resume(NAME).messages)} messages (was {original}): "
          "forks never change it, and neither fork saw the other.")


def resume_day2(client) -> dict:
    session = sessions.resume(NAME)
    old_auth = any("token_expires_at > Date.now()" in r for r in session.tool_results())  # the unfixed line
    print(f"Resumed '{NAME}': {len(session.messages)} messages, {len(session.tool_results())} tool results restored"
          f"{' - including the OLD auth.js, which no longer matches the file on disk' if old_auth else ''}.")
    carried = len(session.tool_results())
    status, read = run_turn(session, "Good morning, I'm back. " + QUESTION, STATUS_SCHEMA, client, WORKSPACE)
    return report("resume", status, read, carried)


def fresh_day2(client) -> dict:
    summary = json.loads(SUMMARY.read_text(encoding="utf-8"))
    session = sessions.start(NAME + "-day2")
    status, read = run_turn(session, sessions.summary_injection_prompt(summary, CHANGED, QUESTION),
                            STATUS_SCHEMA, client, WORKSPACE)
    return report("fresh + summary", status, read, carried=0)


def report(label: str, status: dict, read: list[str], carried: int) -> dict:
    c = classify(status)
    print(f"\n[{label}] files read this turn: {read or 'none'}")
    for b in status["open_bugs"]:
        mark = "  <-- STALE: this bug was fixed overnight" if is_stale(b) else ""
        print(f"  open: {b['file']}:{b['line']} {b['description']}{mark}")
    for v in status["verified_fixed"]:
        print(f"  fixed: {v}")
    print(f"  fix first: {status['fix_first']}")
    return {"label": label, "read": read, "carried": carried, **c}


def main() -> None:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    client = anthropic.Anthropic() if which != "modify" else None
    if which in ("analyse", "all"):
        print("=== DAY 1: analyse ===")
        analyse(client)
    if which in ("fork", "all"):
        print("\n=== FORK: two strategies from the same baseline ===")
        fork_demo(client)
    if which in ("modify", "all"):
        print(f"\n=== OVERNIGHT: fixed {apply_fixes()} ===")
    rows = []
    if which in ("resume", "all"):
        print("\n=== DAY 2 (trap): --resume ===")
        rows.append(resume_day2(client))
    if which in ("fresh", "all"):
        print("\n=== DAY 2 (fix): fresh start + summary injection ===")
        rows.append(fresh_day2(client))
    if len(rows) == 2:
        print("\n=== COMPARISON ===")
        print(f"{'approach':<16} {'old tool results':>16} {'files read':>10} {'stale claims':>13} "
              f"{'open bugs kept':>15} {'fixes verified':>15}")
        for r in rows:
            print(f"{r['label']:<16} {r['carried']:>16} {len(r['read']):>10} {len(r['stale']):>13} "
                  f"{len(r['kept']):>13}/{len(STILL_OPEN)} {r['verified_fixed']:>15}")


if __name__ == "__main__":
    main()
