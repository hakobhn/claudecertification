"""Build Exercise 1.1 - Multi-tool agent loop.

The loop is controlled by `stop_reason`, never by content type, natural-language
phrases, or an iteration count:
  * "tool_use"  -> run the requested tools, append results, loop again
  * "end_turn"  -> done, return the final text
  * anything else -> "not finished, check why" (pause_turn, max_tokens, refusal, ...)

MAX_ITERATIONS is only a safeguard against runaway agents and should never fire
in normal operation.
"""

from __future__ import annotations

import ast
import json
import logging
import operator
import sys

import anthropic

MODEL = "claude-opus-5-5"
MAX_TOKENS = 16000
MAX_ITERATIONS = 20  # safety cap - NOT the primary stopping mechanism

log = logging.getLogger("agent")

# ---------------------------------------------------------------------------
# Step 1: two tools with JSON Schema input_schema
# ---------------------------------------------------------------------------
TOOLS = [
    {
        "name": "calculator",
        "description": (
            "Evaluate an arithmetic expression and return the numeric result. "
            "Supports + - * / // % ** and parentheses. Use this for any math "
            "instead of computing in your head."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "description": "Arithmetic expression, e.g. '(8849 - 6190) * 2'",
                }
            },
            "required": ["expression"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "name": "web_search",
        "description": (
            "Search the web for facts and figures (populations, heights, prices, "
            "dates). Returns a short list of result snippets. Use this whenever "
            "you need a value you don't already have in the conversation."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "The search query"}
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        "strict": True,
    },
]

# --- calculator: safe AST evaluation (no eval()) ---------------------------
_BIN_OPS = {
    ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
    ast.Div: operator.truediv, ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod, ast.Pow: operator.pow,
}
_UNARY_OPS = {ast.UAdd: operator.pos, ast.USub: operator.neg}


def _eval_node(node: ast.AST) -> float:
    if isinstance(node, ast.Expression):
        return _eval_node(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _BIN_OPS:
        return _BIN_OPS[type(node.op)](_eval_node(node.left), _eval_node(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in _UNARY_OPS:
        return _UNARY_OPS[type(node.op)](_eval_node(node.operand))
    raise ValueError(f"Unsupported expression element: {ast.dump(node)}")


def calculator(expression: str) -> str:
    result = _eval_node(ast.parse(expression, mode="eval"))
    return json.dumps({"expression": expression, "result": result})


# --- web search stub: mock results ------------------------------------------
_MOCK_INDEX = {
    "everest": "Mount Everest is 8,849 metres tall (2020 survey).",
    "kilimanjaro": "Mount Kilimanjaro is 5,895 metres tall.",
    "denali": "Denali is 6,190 metres tall.",
    "tokyo": "The population of Tokyo (city proper) is about 14,000,000.",
    "paris": "The population of Paris (city proper) is about 2,100,000.",
    "speed of light": "The speed of light in vacuum is 299,792 km/s.",
}


def web_search(query: str) -> str:
    q = query.lower()
    hits = [
        {"title": key.title(), "snippet": text, "url": f"https://example.com/{key.replace(' ', '-')}"}
        for key, text in _MOCK_INDEX.items()
        if key in q
    ]
    if not hits:
        hits = [{"title": "No results", "snippet": f"No mock results for '{query}'.", "url": ""}]
    return json.dumps({"query": query, "results": hits})


TOOL_HANDLERS = {"calculator": calculator, "web_search": web_search}


def execute_tool(name: str, tool_input: dict) -> tuple[str, bool]:
    """Run a tool. Returns (content, is_error). Errors go back to Claude, not up the stack."""
    handler = TOOL_HANDLERS.get(name)
    if handler is None:
        return f"Error: unknown tool '{name}'", True
    try:
        return handler(**tool_input), False
    except Exception as exc:  # report to the model so it can recover
        return f"Error: {type(exc).__name__}: {exc}", True


# ---------------------------------------------------------------------------
# Steps 2-4 and 6: the agentic loop
# ---------------------------------------------------------------------------
def _final_text(response) -> str:
    return "".join(b.text for b in response.content if b.type == "text")


def run_agent(user_input: str, client=None, system: str | None = None) -> str:
    client = client or anthropic.Anthropic()
    messages: list[dict] = [{"role": "user", "content": user_input}]
    iterations = 0

    while True:
        iterations += 1
        if iterations > MAX_ITERATIONS:
            # Safety net only. Hitting this means something is wrong (runaway loop).
            log.warning("Safety cap of %d iterations hit; stopping a possibly runaway agent.", MAX_ITERATIONS)
            return "[stopped: safety iteration cap reached before the task finished]"

        # Step 2: send the full conversation history every time.
        kwargs = dict(model=MODEL, max_tokens=MAX_TOKENS, tools=TOOLS, messages=messages)
        if system:
            kwargs["system"] = system
        response = client.messages.create(**kwargs)
        log.info("iteration %d: stop_reason=%s", iterations, response.stop_reason)

        # Step 4: end_turn -> Claude has finished. Return the final text.
        if response.stop_reason == "end_turn":
            return _final_text(response)

        # Step 3: tool_use -> run tools, append assistant turn + tool results, loop.
        if response.stop_reason == "tool_use":
            # Keep the whole assistant content (text + tool_use blocks), unchanged.
            messages.append({"role": "assistant", "content": response.content})

            tool_results = []
            for block in response.content:
                if block.type != "tool_use":
                    continue  # text alongside tool_use is NOT a completion signal
                content, is_error = execute_tool(block.name, block.input)
                log.info("  tool %s(%s) -> %s", block.name, json.dumps(block.input), content)
                result = {"type": "tool_result", "tool_use_id": block.id, "content": content}
                if is_error:
                    result["is_error"] = True
                tool_results.append(result)

            # All results in ONE user message.
            messages.append({"role": "user", "content": tool_results})
            continue

        # Server-side tool turn paused: append it and re-send to let Claude continue.
        if response.stop_reason == "pause_turn":
            messages.append({"role": "assistant", "content": response.content})
            continue

        # Every other value means "not finished, check why", never "done".
        if response.stop_reason == "refusal":
            details = getattr(response, "stop_details", None)
            category = getattr(details, "category", None) if details else None
            raise RuntimeError(f"Claude declined the request (refusal, category={category}).")
        if response.stop_reason in ("max_tokens", "model_context_window_exceeded"):
            raise RuntimeError(f"Response truncated (stop_reason={response.stop_reason}).")
        raise RuntimeError(f"Unhandled stop_reason: {response.stop_reason!r}")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    # Step 5: a prompt that needs sequential tool calls (search, then calculate).
    prompt = " ".join(sys.argv[1:]) or (
        "How much taller is Mount Everest than Denali, in metres? "
        "Look up both heights, then use the calculator for the difference."
    )
    print(run_agent(prompt))


if __name__ == "__main__":
    main()
