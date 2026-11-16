# Subagent Invocation and Context Passing — Build Exercise 1.3

A coordinator that spawns research subagents, passes their structured results to a synthesis subagent and
keeps source attribution intact. Built twice, in Python and in Java, for lesson 1.3
(https://claudecertificationguide.com/learn/1-agentic-architecture/1-3-subagent-invocation-context).

```
subagentContext/
├── subagentContextPython/
│   ├── coordinator.py        THE COORDINATOR: options, Task tool, parallel spawning, context passing, verify (+ main)
│   ├── agents.py             Finding type, the 3 AgentDefinitions, their tools, the agent loop
│   ├── test_coordinator.py   offline tests (fake client)
│   └── requirements.txt
└── subagentContextJava/
    ├── pom.xml               Maven (anthropic-java 3.8.0, JUnit 5, runnable -all.jar)
    └── src/main/java/com/example/subagentcontext/
        ├── Coordinator.java       THE COORDINATOR (same steps as coordinator.py)
        ├── Agents.java            the 3 AgentDefinitions, their tools, the agent loop
        ├── AgentDefinition.java   description + system prompt + restricted tools
        ├── Finding.java           step 3: content separated from source metadata
        ├── Synthesis.java         the report + every claim with its citation
        ├── MessageSender.java     one Claude call (tests pass a fake)
        ├── Json.java              JSON / JSON Schema helpers
        └── Main.java              entry point
```

## Architecture

```
                   question
                      │
              ┌───────▼────────┐   allowedTools = ["Task"]  ← without it, nothing can be spawned
              │  COORDINATOR   │
              └─┬──────────┬───┘
   ONE response,│          │two Task calls → run in PARALLEL
        ┌───────▼─────┐ ┌──▼────────────────┐
        │ web_research│ │ document_analysis │   each: fresh context, own system prompt,
        │ web_search  │ │ list/read_document│         only its own tools
        └───────┬─────┘ └──┬────────────────┘
   final message│          │final message  (tool calls/results never reach the coordinator)
                ▼          ▼
         findings: [{claim, analysis, source_url, source_title, document_name, page_number,
                     confidence, retrieved_by}, ...]
                      │  COMPLETE, metadata intact
              ┌───────▼────────┐
              │   synthesis    │   no tools; must cite a source for every claim
              └───────┬────────┘
                      ▼
            report + claims  →  verify: every claim cites a URL / document page from the findings
```

## The six steps, and where they are

| Step | Python | Java |
|---|---|---|
| 1. Coordinator with `Task` in `allowedTools`, subagents in `options.agents` | `CoordinatorOptions`, `task_tool()` | `Coordinator.Options`, `taskTool()` |
| 2. Two subagents, each an `AgentDefinition` with **restricted tools** | `agents.WEB_RESEARCH`, `agents.DOCUMENT_ANALYSIS` | `Agents.WEB_RESEARCH`, `Agents.DOCUMENT_ANALYSIS` |
| 3. Structured `Finding`: content (`claim`, `analysis`) apart from metadata | `agents.Finding` | `Finding` |
| 4. Pass **complete** findings, metadata intact, to synthesis | `Coordinator._spawn` | `Coordinator.spawn` |
| 5. Verify every claim cites a real source | `verify_attribution()` | `Coordinator.verifyAttribution()` |
| 6. Spawn independent subagents in **one** response, run them in parallel | `Coordinator._run_tasks` | `Coordinator.runTasks` |

### Rules the code follows

| Rule | How |
|---|---|
| Subagents inherit **nothing** | every subagent starts a new conversation whose only user message is the Task `prompt` |
| Only the **final message** returns | the coordinator's `tool_result` for a Task call is the subagent's final JSON, never its tool calls |
| So the prompt must **name the fields to return** | both research system prompts list every `Finding` field; a JSON Schema enforces them |
| Prompts give **goals and quality criteria**, not step-by-step procedures | see the three system prompts in `agents.py` / `Agents.java` |
| Each agent's tools are **scoped to its role** | `restricted_tool_runner` rejects any tool outside the agent's definition |

## The exam's favourite failure: "the synthesis has no sources"

Run with `--strip-metadata` and the hub passes only `claim` + `analysis` to synthesis, as a buggy coordinator
would. The research subagents still work perfectly, but no claim in the report can be traced to a source:

```
Findings: 2, claims: 2, unattributed claims: 2
```

The root cause is **context passing**, not the synthesis prompt. If the metadata never reaches the
synthesis agent, no prompt wording can make it cite sources. The `test_stripped_metadata_breaks_attribution`
test shows exactly this.

## Agent SDK vs. this code

The lesson uses the **Claude Agent SDK**. There, the coordinator is a `query()` call, `Task` (called `Agent`
in newer versions) is a built-in tool, and subagents are declared like this:

```python
options = ClaudeAgentOptions(
    allowed_tools=["Task"],                       # step 1
    agents={"web_research": AgentDefinition(      # step 2
        description="...", prompt="...", tools=["WebSearch"])},
)
```

This project builds the **same mechanism on the Messages API**, as lessons 1.1 and 1.2 did. That way every
moving part is visible and testable offline, and the Java version, which has no Agent SDK, works the same
way:

| Agent SDK | Here |
|---|---|
| `allowed_tools=["Task"]` | `CoordinatorOptions.allowed_tools`; the Task tool is offered only when it is listed |
| `agents={name: AgentDefinition(...)}` | `CoordinatorOptions.agents` → the `subagent_type` enum and the Task description |
| SDK runs the subagent | `_spawn` / `spawn` runs a new agent loop with that definition |
| Parallel Task calls in one turn | all `tool_use` blocks of one response run on a thread pool |

**Not built here: `fork_session`.** A fork branches a *session* from a shared baseline, for example to try two
approaches after one analysis, and the branches never see each other. That is different from parallel Task
calls, which start *fresh* subagents with nothing but their prompt. In the SDK you resume with
`fork_session=True`; on the CLI you use `--resume <id> --fork-session`.

## Running

Both use your `ant auth login` sign-in (or `ANTHROPIC_API_KEY` if it is set).

```
# Python
cd subagentContextPython
pip install -r requirements.txt
python coordinator.py                      # default question about offshore wind
python coordinator.py --strip-metadata     # reproduce the attribution bug
python -m unittest -v                      # offline tests

# Java
cd subagentContextJava
mvn test                                   # offline tests
mvn package
java -jar target/subagent-context-java-1.0.0-all.jar
java -jar target/subagent-context-java-1.0.0-all.jar --strip-metadata
```
