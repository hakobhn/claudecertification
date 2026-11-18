"""The logistics company's tools. Deliberately messy: three tools, three data formats.

  get_customer   (Tool A): Unix timestamps,  numeric status codes     (200)
  lookup_order   (Tool B): ISO 8601 strings, English status strings   ("pending")
  check_shipping (Tool C): DD/MM/YYYY,       single-character codes   ("S")

The tools know nothing about hooks. Every handler records that it ran in
`session.executed`, so tests can prove a blocked tool never executed.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Session:
    executed: list[str] = field(default_factory=list)    # which tool handlers actually ran
    aml_passed: bool = False                              # set by a PostToolUse hook on aml_check
    escalations: list[dict] = field(default_factory=list)  # refunds redirected to humans


def _string(description):
    return {"type": "string", "description": description}


def _tool(name, description, **properties):
    return {"name": name, "description": description, "strict": True,
            "input_schema": {"type": "object", "properties": properties,
                             "required": list(properties), "additionalProperties": False}}


TOOLS = [
    _tool("get_customer", "Get a customer account.", customer_id=_string("e.g. C-1001")),
    _tool("lookup_order", "Get an order: amount, status and when it was placed.", order_id=_string("e.g. O-7001")),
    _tool("check_shipping", "Get the shipping status and dates for an order.", order_id=_string("e.g. O-7001")),
    _tool("process_refund", "Refund an order to the customer.", order_id=_string("Order ID"),
          amount={"type": "number", "description": "Amount in USD"}),
    _tool("aml_check", "Run the anti-money-laundering check on a sender. Required before any transfer.",
          sender_id=_string("e.g. S-88")),
    _tool("transfer_funds", "Send cash-on-delivery money collected for an order to its sender.",
          order_id=_string("Order ID"), sender_id=_string("Sender ID"),
          amount={"type": "number", "description": "Amount in USD"}),
]


ORDERS = {  # O-7001: parcel lost in transit.  O-7002: delivered, cash on delivery collected.
    "O-7001": {"status": "active", "shipping": "L", "cod_collected": False},
    "O-7002": {"status": "active", "shipping": "D", "cod_collected": True},
}


def run_tool(name: str, args: dict, session: Session) -> dict:
    """The raw tools, with their raw, inconsistent output."""
    session.executed.append(name)
    order = ORDERS.get(args.get("order_id"), {"status": "pending", "shipping": "P", "cod_collected": False})
    if name == "get_customer":
        return {"customer_id": args["customer_id"], "name": "Ana Silva",
                "created_at": 1710489600, "status": 200}              # Tool A: epoch + numeric code
    if name == "lookup_order":
        return {"order_id": args["order_id"], "amount": 900.00, "currency": "USD",
                "placed_at": "2024-03-15T12:00:00Z", "status": order["status"],
                "cash_on_delivery_collected": order["cod_collected"]}   # Tool B: ISO + string
    if name == "check_shipping":
        return {"order_id": args["order_id"], "shipped_on": "03/04/2024",
                "eta": "07/04/2024", "status": order["shipping"]}       # Tool C: DD/MM/YYYY + 1 char
    if name == "process_refund":
        return {"refund_id": "R-1", "order_id": args["order_id"], "amount": args["amount"], "status": "processed"}
    if name == "aml_check":
        return {"sender_id": args["sender_id"], "result": "pass"}
    if name == "transfer_funds":
        return {"transfer_id": "T-1", "amount": args["amount"], "sender_id": args["sender_id"], "status": "sent"}
    return {"error": f"unknown tool {name}"}
