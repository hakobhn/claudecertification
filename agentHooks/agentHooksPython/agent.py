"""Build Exercise 1.5 - Hooks for data normalisation and policy enforcement.

The loop is lesson 1.1's. The only change: tools run through hooks.run_with_hooks().

    python agent.py demo        # hooks without the model (no API calls)
    python agent.py normalise   # needs all three tools -> one clean format
    python agent.py refund      # $900 refund -> blocked, escalated
    python agent.py transfer    # transfer -> blocked until AML passes
    python agent.py             # all of the above
"""

import json
import sys

import anthropic

from hooks import run_with_hooks
from tools import TOOLS, Session, run_tool

MODEL = "claude-opus-5-5"
SYSTEM = ("You are the support agent of a logistics company. Use the tools to answer. "
          "Reply in plain text, briefly.")  # style = prompt guidance (low stakes, no hook needed)


def run_agent(question: str, client, session: Session) -> str:
    messages = [{"role": "user", "content": question}]
    for _ in range(20):  # safety cap only
        response = client.messages.create(model=MODEL, max_tokens=16000, system=SYSTEM,
                                          tools=TOOLS, messages=messages)
        if response.stop_reason == "end_turn":
            return "".join(b.text for b in response.content if b.type == "text")
        if response.stop_reason != "tool_use":
            raise RuntimeError(f"stop_reason={response.stop_reason}")

        messages.append({"role": "assistant", "content": response.content})
        results = []
        for block in response.content:
            if block.type == "tool_use":
                content, is_error = run_with_hooks(block.name, block.input, session)
                print(f"  {block.name}({json.dumps(block.input)}) -> {'DENIED: ' if is_error else ''}{content}")
                results.append({"type": "tool_result", "tool_use_id": block.id, "is_error": is_error,
                                "content": content if is_error else json.dumps(content)})
        messages.append({"role": "user", "content": results})
    return "[safety cap reached]"


def demo() -> None:
    print("\n=== DEMO: hooks without the model ===")
    s = Session()
    print("\nPostToolUse - what the tool returns vs what the model sees:")
    for tool in ("get_customer", "lookup_order", "check_shipping"):
        args = {"customer_id": "C-1001"} if tool == "get_customer" else {"order_id": "O-7002"}
        print(f"  {tool:<15} raw:   {run_tool(tool, args, Session())}")
        print(f"  {'':<15} model: {run_with_hooks(tool, args, s)[0]}")

    print("\nPreToolUse - blocked calls never reach the tool:")
    refund = {"order_id": "O-7001", "amount": 900.0}
    transfer = {"order_id": "O-7002", "sender_id": "S-88", "amount": 900.0}
    s = Session()
    print(f"  refund $900          -> {run_with_hooks('process_refund', refund, s)[0]}")
    print(f"  refund $120          -> {run_with_hooks('process_refund', dict(refund, amount=120.0), s)[0]}")
    print(f"  transfer before AML  -> {run_with_hooks('transfer_funds', transfer, s)[0]}")
    print(f"  aml_check            -> {run_with_hooks('aml_check', {'sender_id': 'S-88'}, s)[0]}")
    print(f"  transfer after AML   -> {run_with_hooks('transfer_funds', transfer, s)[0]}")
    print(f"  tools that actually ran: {s.executed}")


SCENARIOS = {
    "normalise": "For customer C-1001 and order O-7002: is the account active, when was the order placed, "
                 "when did it ship and when was it due to arrive?",
    "refund": "The parcel for order O-7001 ($900) was lost. Refund the customer the full $900 right now.",
    "transfer": "Transfer the $900 cash-on-delivery we collected for order O-7002 to sender S-88 now.",
}


def main() -> None:
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("demo", "all"):
        demo()
    client = anthropic.Anthropic() if which != "demo" else None
    for name, question in SCENARIOS.items():
        if which in (name, "all"):
            print(f"\n=== {name.upper()}: {question}")
            session = Session()
            print("\n" + run_agent(question, client, session))
            print(f"\n[tools that actually ran: {session.executed} | escalations: {session.escalations}]")


if __name__ == "__main__":
    main()
