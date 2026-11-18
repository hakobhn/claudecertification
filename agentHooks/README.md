# Agent SDK Hooks — Build Exercise 1.5

PreToolUse and PostToolUse hooks for a logistics company's support agent, built twice (Python and
Java), for lesson 1.5
(https://claudecertificationguide.com/learn/1-agentic-architecture/1-5-agent-sdk-hooks).

Kept deliberately small. **Read `hooks.py` / `Hooks.java` first**: that file *is* the lesson.

```
agentHooks/
├── agentHooksPython/
│   ├── hooks.py        THE LESSON: the hooks, their registration, and the runner
│   ├── tools.py        six tools with deliberately mixed data formats
│   ├── agent.py        the lesson 1.1 loop, calling tools through the hooks + scenarios
│   └── test_hooks.py   offline tests
└── agentHooksJava/src/main/java/com/example/hooks/
    ├── Hooks.java      same as hooks.py
    ├── Tools.java      same as tools.py
    └── Agent.java      same as agent.py
```

## The one picture to remember

```
      model asks for a tool
              │
              ▼
      ┌───────────────┐   deny ──► the tool NEVER runs. The model gets the reason.
      │  PreToolUse   │            Use for: POLICY (block before the side effect)
      └───────┬───────┘
              │ allow
              ▼
          tool runs  ◄── side effect happens here (money moves)
              │
              ▼
      ┌───────────────┐
      │  PostToolUse  │ ──► may replace the result (updatedToolOutput)
      └───────┬───────┘     Use for: NORMALISING DATA, recording state
              ▼
      model sees the result
```

**PostToolUse can never block an action**: by the time it runs, the money has already moved.
That's the exam's favourite trap.

## The four hooks in this exercise

| Hook | Type | Matcher | What it does |
|---|---|---|---|
| `refund_limit` | PreToolUse | `process_refund` | amount > $500 → **deny**, send to the human escalation queue |
| `aml_gate` | PreToolUse | `transfer_funds` | no passing AML check yet → **deny**, "call aml_check first" |
| `record_aml_pass` | PostToolUse | `aml_check` | result is "pass" → remember it in the session (`aml_gate` reads this) |
| `normalise_result` | PostToolUse | `*` (all tools) | rewrites dates to ISO 8601 and status codes to English |

`record_aml_pass` + `aml_gate` together form the lesson's prerequisite gate: a PostToolUse hook *records*
that the prerequisite happened, and a PreToolUse hook *enforces* it.

## Normalisation: what the tool returns vs. what the model sees

| Tool | Raw output | After `normalise_result` |
|---|---|---|
| `get_customer` (A) | `created_at: 1710489600`, `status: 200` | `"2024-03-15T08:00:00Z"`, `"active"` |
| `lookup_order` (B) | `placed_at: "2024-03-15T12:00:00Z"`, `status: "active"` | unchanged (already clean) |
| `check_shipping` (C) | `shipped_on: "03/04/2024"`, `status: "D"` | `"2024-04-03T00:00:00Z"`, `"delivered"` |

`03/04/2024` is the dangerous one: it means **3 April**, but a model could read it as March 4. After the
hook, there is nothing left for the model to misread.

## Hooks vs. prompts

| Requirement | Mechanism | In this code |
|---|---|---|
| Must hold 100% of the time (money, compliance) | **Hook**: deterministic | refund limit, AML gate |
| Preferred, an occasional slip is fine | **Prompt**: probabilistic | "Reply in plain text, briefly." in `SYSTEM` |

## What the real runs showed

- **normalise:** the model received clean ISO dates and English statuses from all three tools and
  answered correctly: shipped 3 April, due 7 April, delivered.
- **refund:** the model called `process_refund($900)`. The **PreToolUse hook denied it**,
  `process_refund` is **not** among the tools that actually ran, the refund went to the escalation
  queue, and the model told the customer staff would approve it.
- **transfer:** the model called `aml_check` *before* `transfer_funds` on its own, so the AML gate
  didn't need to fire. The `demo` and the tests show it blocking a transfer that comes first.

A first version had mock data that contradicted the scenarios: the "lost" parcel showed as shipped,
and the "collected" cash as not yet delivered. The model then refused to act at all, so the hooks
never ran. Good agents check facts, so the test data has to be consistent too.

## About the Agent SDK

The lesson's exercise uses the **Claude Agent SDK**, which runs hooks for you. You register a
`HookCallback` for `PreToolUse` / `PostToolUse` and return `permissionDecision: "deny"` or
`updatedToolOutput`. The Agent SDK has no Java version, so this exercise uses a ~30-line runner
(`run_with_hooks` / `runWithHooks`) on top of the Messages API, with **the same names and the same
semantics**. The principle is identical: the hook is code in the tool-call path, not text in a prompt.
SDK docs: https://code.claude.com/docs/en/agent-sdk

## Running

```powershell
cd agentHooksPython
python agent.py demo          # both hook types, no API calls
python agent.py normalise     # or: refund, transfer; no argument = all
python -m unittest -v test_hooks
```

```powershell
cd agentHooksJava
mvn -q compile exec:java "-Dexec.args=demo"      # or normalise / refund / transfer / all
mvn test
```
