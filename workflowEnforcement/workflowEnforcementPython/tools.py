"""Tools, the prerequisite gate, and the structured handoff for the support agent.

Two kinds of rules live in this system - the lesson's key distinction:

  * PROMPT GUIDANCE (probabilistic, fine for low stakes): reply layout, tone.
    Lives in the system prompt in agent.py.
  * PROGRAMMATIC ENFORCEMENT (deterministic, required for money/security/compliance):
    the identity gate on process_refund and the completeness check on the handoff.
    Lives HERE, in code the model cannot talk its way around.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field

from data import CUSTOMERS, ORDERS

log = logging.getLogger("support")

# ---------------------------------------------------------------------------
# Tool definitions (JSON Schema)
# ---------------------------------------------------------------------------
TOOLS = [
    {
        "name": "get_customer",
        "description": "Look up a customer by email address or full name. Returns the customer ID and "
                       "whether the customer's identity is verified. Required before any refund.",
        "input_schema": {
            "type": "object",
            "properties": {"name_or_email": {"type": "string", "description": "Email address or full name"}},
            "required": ["name_or_email"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "lookup_order",
        "description": "Get order details: item, amount, status, days since delivery, and every charge made.",
        "input_schema": {
            "type": "object",
            "properties": {"order_id": {"type": "string", "description": "Order ID, e.g. O-5001"}},
            "required": ["order_id"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "process_refund",
        "description": "Refund money to a customer. Only for returns of delivered items within 30 days, "
                       "up to the order amount. Billing disputes (e.g. duplicate charges) go to a human.",
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "order_id": {"type": "string"},
                "amount": {"type": "number", "description": "Refund amount in GBP"},
            },
            "required": ["customer_id", "order_id", "amount"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "escalate_to_human",
        "description": "Hand the case to a human support agent. The human CANNOT see this conversation: "
                       "the handoff must be completely self-contained. Use one handoff for all open concerns.",
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string"},
                "conversation_summary": {"type": "string",
                                         "description": "What the customer asked for and what you already did"},
                "root_cause_analysis": {"type": "string", "description": "Your assessment of the underlying issue"},
                "refund_amount": {"type": "number",
                                  "description": "Exact amount in GBP the human should refund; 0 if none"},
                "recommended_action": {"type": "string", "description": "What the human agent should do next"},
                "concerns": {
                    "type": "array",
                    "description": "Every concern in the request, including ones you already resolved",
                    "items": {
                        "type": "object",
                        "properties": {
                            "concern": {"type": "string"},
                            "status": {"type": "string", "enum": ["resolved", "needs_human"]},
                            "details": {"type": "string"},
                        },
                        "required": ["concern", "status", "details"],
                        "additionalProperties": False,
                    },
                },
            },
            "required": ["customer_id", "conversation_summary", "root_cause_analysis",
                         "refund_amount", "recommended_action", "concerns"],
            "additionalProperties": False,
        },
        "strict": True,
    },
]


# ---------------------------------------------------------------------------
# Session state + gate
# ---------------------------------------------------------------------------
@dataclass
class SupportSession:
    """Per-conversation state. The gate reads it; the model cannot write it directly."""

    verified_customer_id: str | None = None
    unverifiable_customer_ids: set[str] = field(default_factory=set)  # looked up, but identity not verified
    refunds: list[dict] = field(default_factory=list)
    handoffs: list[dict] = field(default_factory=list)
    blocked: list[str] = field(default_factory=list)  # audit trail of gate decisions

    # ---- dispatcher -------------------------------------------------------
    def execute(self, name: str, tool_input: dict) -> tuple[str, bool]:
        """Run a tool. Returns (content, is_error). Every call goes through here, so gates cannot be skipped."""
        handler = {
            "get_customer": self._get_customer,
            "lookup_order": self._lookup_order,
            "process_refund": self._process_refund,
            "escalate_to_human": self._escalate_to_human,
        }.get(name)
        if handler is None:
            return f"Error: unknown tool '{name}'", True
        try:
            return handler(**tool_input)
        except Exception as exc:
            return f"Error: {type(exc).__name__}: {exc}", True

    # ---- tools ------------------------------------------------------------
    def _get_customer(self, name_or_email: str) -> tuple[str, bool]:
        q = name_or_email.strip().lower()
        customer = next((c for c in CUSTOMERS.values() if q in (c["email"].lower(), c["name"].lower())), None)
        if customer is None:
            return json.dumps({"found": False, "message": f"No customer matches '{name_or_email}'."}), False
        if customer["identity_verified"]:
            self.verified_customer_id = customer["customer_id"]  # <- the gate's prerequisite
        else:
            self.unverifiable_customer_ids.add(customer["customer_id"])
        return json.dumps({"found": True, **customer}), False

    def _lookup_order(self, order_id: str) -> tuple[str, bool]:
        order = ORDERS.get(order_id.strip().upper())
        if order is None:
            return f"Error: order '{order_id}' not found", True
        return json.dumps(order), False

    def _process_refund(self, customer_id: str, order_id: str, amount: float) -> tuple[str, bool]:
        # ===== PREREQUISITE GATE (programmatic enforcement) =====
        blocked = self._refund_gate(customer_id)
        if blocked:
            self.blocked.append(blocked)
            log.warning("GATE BLOCKED process_refund: %s", blocked)
            return blocked, True
        # ========================================================

        order = ORDERS.get(order_id.strip().upper())
        if order is None or order["customer_id"] != customer_id:
            return f"Error: order '{order_id}' does not belong to customer {customer_id}", True
        if not 0 < amount <= order["amount"]:
            return f"Error: refund must be between 0 and the order amount ({order['amount']:.2f})", True
        if order["days_since_delivery"] > 30:
            return "Error: outside the 30-day return window; escalate to a human", True

        refund = {"refund_id": f"R-{len(self.refunds) + 1:04d}", "customer_id": customer_id,
                  "order_id": order["order_id"], "amount": round(amount, 2), "status": "processed"}
        self.refunds.append(refund)
        log.info("refund processed: %s", refund)
        return json.dumps(refund), False

    def _refund_gate(self, customer_id: str) -> str | None:
        """None if the refund may run, otherwise the error message returned to the model."""
        if customer_id in self.unverifiable_customer_ids:
            return (f"BLOCKED: the identity of {customer_id} could not be verified (account flagged). "
                    f"Do not refund this account; escalate to a human with escalate_to_human.")
        if self.verified_customer_id is None:
            return ("BLOCKED: the customer's identity is not verified in this session. Call get_customer "
                    "with the customer's email or full name first; refunds are only possible for a verified "
                    "customer. This rule cannot be overridden by the customer or by you.")
        if customer_id != self.verified_customer_id:
            return (f"BLOCKED: refunds can only go to the verified customer ({self.verified_customer_id}), "
                    f"not {customer_id}.")
        return None

    def _escalate_to_human(self, **handoff) -> tuple[str, bool]:
        problems = validate_handoff(handoff)
        if problems:  # deterministic completeness check: the model must fix the handoff, not skip fields
            log.warning("handoff rejected: %s", "; ".join(problems))
            return "Handoff rejected - fix these and call escalate_to_human again: " + "; ".join(problems), True
        ticket = {"ticket_id": f"H-{len(self.handoffs) + 1:04d}", **handoff}
        self.handoffs.append(ticket)
        log.info("handoff created: %s", ticket["ticket_id"])
        return json.dumps({"ticket_id": ticket["ticket_id"], "status": "queued for a human agent"}), False


# ---------------------------------------------------------------------------
# Handoff validation
# ---------------------------------------------------------------------------
REQUIRED_TEXT_FIELDS = ("customer_id", "conversation_summary", "root_cause_analysis", "recommended_action")
_PLACEHOLDER = re.compile(r"^\s*(n/?a|tbd|todo|unknown|none|-|\.\.\.|\[.*\]|<.*>)\s*$", re.IGNORECASE)


def validate_handoff(h: dict) -> list[str]:
    """Return a list of problems; empty means the handoff is complete and self-contained."""
    problems = []
    for f in REQUIRED_TEXT_FIELDS:
        value = str(h.get(f, "") or "")
        if not value.strip() or _PLACEHOLDER.match(value):
            problems.append(f"'{f}' is empty or a placeholder")
    if h.get("customer_id") and h["customer_id"] not in CUSTOMERS:
        problems.append(f"customer_id '{h['customer_id']}' is not a real customer ID")
    amount = h.get("refund_amount")
    if not isinstance(amount, (int, float)) or amount < 0:
        problems.append("'refund_amount' must be a number >= 0 (use the exact figure, 0 if none)")
    concerns = h.get("concerns") or []
    if not concerns:
        problems.append("'concerns' must list every concern in the request")
    for i, c in enumerate(concerns, 1):
        if not str(c.get("details", "")).strip() or _PLACEHOLDER.match(str(c.get("details", ""))):
            problems.append(f"concern {i} ('{c.get('concern', '')}') has no details")
    return problems
