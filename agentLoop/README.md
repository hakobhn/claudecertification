# Agentic Loop — Build Exercise 1.1

The same agent built twice, in Python and in Java, for lesson 1.1 "Agentic Loops"
(https://claudecertificationguide.com/learn/1-agentic-architecture/1-1-agentic-loops).

```
AgenticExam/
├── agentLoopPython/
│   ├── agent.py            tools + loop + main, in one file
│   ├── test_agent.py       offline tests (fake client)
│   └── requirements.txt
└── agentLoopJava/
    ├── pom.xml             Maven build (anthropic-java 3.8.0, JUnit 5)
    └── src/
        ├── main/java/com/example/agentloop/
        │   ├── Main.java        entry point: builds the client, runs the agent
        │   ├── AgentLoop.java   THE LOOP: stop_reason drives everything
        │   ├── Tools.java       tool definitions (JSON Schema) + execution
        │   └── Calculator.java  safe arithmetic parser used by the calculator tool
        └── test/java/com/example/agentloop/
            └── AgentLoopTest.java   offline tests (fake sender)
```

## How the loop works

```
 ┌──────────────────────────────────────────────────────────────┐
 │ messages = [ user: "How much taller is Everest than Denali?"] │
 └──────────────────────────────────────────────────────────────┘
                 │
                 ▼
  ┌─► send full history + tool definitions to Claude
  │              │
  │              ▼
  │      check response.stop_reason
  │       ├── "tool_use"  ──► 1. append the assistant turn (text + tool_use blocks)
  │       │                    2. run each requested tool in YOUR code
  │       │                    3. append ONE user message with all tool_result blocks
  │       │                       (tool_use_id must match; failures get is_error=true)
  └───────┘                    4. loop
          ├── "end_turn"  ──► return the final text. DONE.
          ├── "pause_turn" ─► append the turn and send again
          └── anything else (max_tokens, refusal, ...) ─► "not finished, check why" → error
```

A real run (both languages produce the same trace):

```
iteration 1: stop_reason=tool_use   → web_search("Everest height"), web_search("Denali height")
iteration 2: stop_reason=tool_use   → calculator("8849 - 6190") = 2659
iteration 3: stop_reason=end_turn   → "Mount Everest is 2,659 metres taller than Denali."
```

### Exam rules this code follows

| Rule | Where |
|---|---|
| `stop_reason` is the only loop control | `agent.py` `run_agent` / `AgentLoop.run` |
| Text next to `tool_use` is **not** "done" (anti-pattern 3) | the tool loop skips non-tool blocks; tests cover it |
| No "I'm done" phrase parsing (anti-pattern 1) | nothing reads the text until `end_turn` |
| Iteration cap is a safeguard only (anti-pattern 2) | `MAX_ITERATIONS = 20`, logs a warning if hit |
| Tool results are always appended to history | `messages.append(...)` / `messages.add(...)` |
| Claude picks the tools (model-driven) | no hard-coded tool order anywhere |

## Python ↔ Java side by side

| Concept | Python (`anthropic`) | Java (`com.anthropic`) |
|---|---|---|
| Client | `anthropic.Anthropic()` | `AnthropicOkHttpClient.fromEnv()` |
| Tool definition | dict with `input_schema` | `Tool.builder()...inputSchema(...)` |
| Send request | `client.messages.create(**kwargs)` | `client.messages().create(params)` |
| Stop reason | `response.stop_reason == "tool_use"` | `response.stopReason()` equals `StopReason.TOOL_USE` |
| Append assistant turn | `{"role": "assistant", "content": response.content}` | `response.toParam()` |
| Find tool calls | `block.type == "tool_use"` | `block.isToolUse()` / `block.asToolUse()` |
| Tool input | `block.input` (dict) | `toolUse._input().convert(...)` → `Map` |
| Tool result | `{"type": "tool_result", "tool_use_id": ..., "content": ...}` | `ToolResultBlockParam.builder().toolUseId(..).content(..)` |
| Results message | `{"role": "user", "content": tool_results}` | `MessageParam.ofUserOfContentBlockParams(list)` |

## Running

Both use your `ant auth login` sign-in automatically (or `ANTHROPIC_API_KEY` if it is set).

Python:

```powershell
cd agentLoopPython
python agent.py
python agent.py "What is the population of Tokyo divided by the population of Paris?"
python -m unittest -v test_agent      # offline, no API calls
```

Java (JDK 25+ and Maven):

```powershell
cd agentLoopJava
mvn -q compile exec:java
mvn -q compile exec:java "-Dexec.args=What is the population of Tokyo divided by the population of Paris?"
mvn test                               # offline, no API calls

# Runnable jar (SDK included): built by mvn package
mvn clean package
java -jar target\agent-loop-java-1.0.0-all.jar
java -jar target\agent-loop-java-1.0.0-all.jar "What is the population of Tokyo divided by the population of Paris?"
```

Each run makes about three requests to `claude-opus-5-5` and costs a few cents.
