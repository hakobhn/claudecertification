# Workflow Enforcement and Handoff — Build Exercise 1.4

A customer support agent for an online clothing shop with a **prerequisite gate** on refunds and a
**structured handoff** to human agents, built twice (Python and Java), for lesson 1.4
(https://claudecertificationguide.com/learn/1-agentic-architecture/1-4-workflow-enforcement-handoff).

```
workflowEnforcement/
├── workflowEnforcementPython/
│   ├── tools.py         tool definitions + SupportSession: THE GATE and the handoff check
│   ├── agent.py         the agentic loop (lesson 1.1) + the gate / bypass / multi scenarios
│   ├── data.py          mock customers and orders
│   ├── test_agent.py    offline tests (gate, handoff check, scripted misbehaving model)
│   └── requirements.txt
└── workflowEnforcementJava/
    ├── pom.xml          Maven (anthropic-java 3.8.0, JUnit 5, runnable -all.jar)
    └── src/main/java/com/example/support/
        ├── SupportSession.java  THE GATE and the handoff check (same as tools.py)
        ├── SupportTools.java    the four tool definitions
        ├── SupportAgent.java    the agentic loop + system prompt
        ├── Data.java            mock customers and orders
        └── Main.java            gate / bypass / multi scenarios
```

## The core idea: a prompt instruction vs. a gate in code

| | Prompt guidance | Programmatic enforcement |
|---|---|---|
| What it is | an instruction in the system prompt | code that runs on every tool call |
| Reliability | probabilistic (the lesson's example: 92%) | deterministic (100%) |
| Can the model skip it? | yes, sometimes | no |
| Use for | low stakes: reply layout, tone | **money, security, compliance** |
| In this code | "Reply style…" in the system prompt | `SupportSession` gate + handoff check |

The system prompt *also* says "verify before refunding". The gate doesn't replace the prompt; it
**guarantees** the order of steps when the prompt fails.

## How the gate works

Every tool call goes through `SupportSession.execute()`. The model has no other way to run a tool.

```
process_refund(customer_id, order_id, amount)
        │
        ▼
 identity of customer_id flagged as unverifiable? ── yes ─► BLOCKED: "do not refund, escalate"
        │ no
        ▼
 has get_customer returned a VERIFIED customer? ──── no ──► BLOCKED: "call get_customer first"
        │ yes
        ▼
 customer_id == the verified customer? ───────────── no ──► BLOCKED: "only the verified customer"
        │ yes
        ▼
 business rules (order owner, amount ≤ order, 30 days) ─► refund processed
```

A blocked call comes back to the model as a `tool_result` with `is_error: true` and tells it **what to
do next**. So when the model skips verification, it verifies and retries rather than giving up. The
offline test `model_skips_verification_gate_blocks_then_model_recovers` scripts exactly that.

## The structured handoff

The human agent **cannot see the conversation**, so `escalate_to_human` requires all five fields from the
lesson, plus the list of concerns:

| Field | Example from a real run |
|---|---|
| `customer_id` | `C-1001` |
| `conversation_summary` | what was asked and what the agent already did (refund R-0001, £129.00) |
| `root_cause_analysis` | "O-5002 shows two charges of £89.50 against an £89.50 order…" |
| `refund_amount` | `89.5`: the exact figure, not "the extra charge" |
| `recommended_action` | "Refund one £89.50 charge… update the address to 12 Elm Street…" |
| `concerns[]` | each concern with `resolved` / `needs_human` and details |

This is **also enforced in code**. `validate_handoff()` rejects empty fields, placeholders (`TBD`, `N/A`,
`[customer]`) and unknown customer IDs, and the model must fix the handoff and send it again.

## Multi-concern requests

> "1) return the overcoat from O-5001, 2) I was charged twice for O-5002, 3) change my address"

What the real run did, as the lesson prescribes:

1. **Decompose**: it found all three concerns.
2. **Investigate in parallel using shared context**: `get_customer`, `lookup_order(O-5001)` and
   `lookup_order(O-5002)` in **one** turn.
3. **Resolve what it may**: the return refund (£129.00) passed the gate.
4. **Hand off the rest in ONE handoff**: the duplicate charge (£89.50) and the address change.
5. **Reply once**, covering all three concerns.

## Running

Both use your `ant auth login` sign-in, or `ANTHROPIC_API_KEY` if it is set.

```powershell
cd workflowEnforcementPython
python agent.py gate       # deterministic gate demo, no API calls
python agent.py bypass     # customer pushes the agent to skip verification
python agent.py multi      # return + billing dispute + address change -> handoff
python agent.py            # all three
python -m unittest -v test_agent
```

```powershell
cd workflowEnforcementJava
mvn -q compile exec:java "-Dexec.args=gate"      # or bypass / multi; no argument = all
mvn test
mvn clean package; java -jar target\workflow-enforcement-java-1.0.0-all.jar multi
```

### What the bypass run shows

In the real runs, Claude Opus 5.5 **verified anyway**, even though the customer told it to skip the
check. The prompt instruction held, so the gate never had to fire. That's the lesson's point: a good
prompt works *most* of the time. For the cases where it doesn't (the lesson's 8%), the `gate` demo and
the offline tests show the gate blocking the refund deterministically, whatever the model decides.
