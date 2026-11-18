"""Hooks: deterministic code that runs around every tool call.

    model asks for a tool
          │
          ▼
    ┌─────────────┐  deny  ─► the tool NEVER runs; the model gets the reason
    │ PreToolUse  │
    └─────┬───────┘  allow
          ▼
       tool runs
          ▼
    ┌─────────────┐
    │ PostToolUse │  ─► may replace the result (updatedToolOutput)
    └─────┬───────┘
          ▼
    model sees the (clean) result

Names follow the Claude Agent SDK: PreToolUse / PostToolUse, permissionDecision
"deny" + permissionDecisionReason, and updatedToolOutput. The SDK runs hooks for
you; this tiny runner (run_with_hooks) just makes the mechanism visible.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone

from tools import Session, run_tool

REFUND_LIMIT = 500.00


# ---------------------------------------------------------------- PreToolUse: policy enforcement
def refund_limit(args: dict, session: Session) -> dict | None:
    """Block refunds above $500 and redirect them to a human."""
    if args["amount"] > REFUND_LIMIT:
        session.escalations.append({"order_id": args["order_id"], "amount": args["amount"]})
        return {"permissionDecision": "deny",
                "permissionDecisionReason": f"Refunds above ${REFUND_LIMIT:.0f} need staff approval. "
                                            f"The ${args['amount']:.2f} refund was NOT processed; it has "
                                            f"been sent to the human escalation queue. Tell the customer."}
    return None  # None = allow


def aml_gate(args: dict, session: Session) -> dict | None:
    """Block transfers until aml_check has passed in this session."""
    if not session.aml_passed:
        return {"permissionDecision": "deny",
                "permissionDecisionReason": "Transfer blocked: no passing AML check in this session. "
                                            "Call aml_check for the sender first, then retry."}
    return None


# ---------------------------------------------------------------- PostToolUse: data normalisation
STATUS_CODES = {200: "active", 404: "not found", 500: "error",
                "S": "shipped", "P": "pending", "D": "delivered", "L": "lost in transit"}
DD_MM_YYYY = re.compile(r"^(\d{2})/(\d{2})/(\d{4})$")


def normalise(value, key=""):
    if isinstance(value, dict):
        return {k: normalise(v, k) for k, v in value.items()}
    if key.endswith("_at") and isinstance(value, int):                    # Unix timestamp -> ISO 8601
        return datetime.fromtimestamp(value, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if isinstance(value, str) and (m := DD_MM_YYYY.match(value)):          # DD/MM/YYYY -> ISO 8601
        day, month, year = m.groups()
        return f"{year}-{month}-{day}T00:00:00Z"
    if key == "status":                                                    # codes -> English
        return STATUS_CODES.get(value, str(value).lower())
    return value


def normalise_result(result: dict, session: Session) -> dict:
    return {"updatedToolOutput": normalise(result)}


def record_aml_pass(result: dict, session: Session) -> None:
    """Set the state that aml_gate checks. Observes only: returns no updatedToolOutput."""
    if result.get("result") == "pass":
        session.aml_passed = True


# ---------------------------------------------------------------- registration (matcher -> hook)
HOOKS = {
    "PreToolUse": [("process_refund", refund_limit),
                   ("transfer_funds", aml_gate)],
    "PostToolUse": [("aml_check", record_aml_pass),
                    ("*", normalise_result)],       # "*" = every tool
}


def run_with_hooks(name: str, args: dict, session: Session) -> tuple[dict | str, bool]:
    """Returns (content for the model, is_error)."""
    for matcher, hook in HOOKS["PreToolUse"]:
        if matcher in ("*", name):
            decision = hook(args, session)
            if decision and decision["permissionDecision"] == "deny":
                return decision["permissionDecisionReason"], True      # tool never runs

    result = run_tool(name, args, session)

    for matcher, hook in HOOKS["PostToolUse"]:
        if matcher in ("*", name):
            output = hook(result, session)
            if output and "updatedToolOutput" in output:
                result = output["updatedToolOutput"]
    return result, False
