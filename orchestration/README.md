# Multi-Agent Orchestration — Build Exercise 1.2

A hub-and-spoke research coordinator, built twice (Python and Java), for lesson 1.2
"Multi-Agent Orchestration"
(https://claudecertificationguide.com/learn/1-agentic-architecture/1-2-orchestration-patterns).

```
orchestration/
├── orchestrationPython/
│   ├── coordinator.py       THE HUB: decompose → delegate → evaluate → refine → report (+ main)
│   ├── subagents.py         the two spokes and the prompts the coordinator builds for them
│   ├── documents.py         small internal document library (for document analysis)
│   ├── llm.py               one Claude call = one fresh conversation (stop_reason checked)
│   ├── test_coordinator.py  offline tests (fake LLM)
│   └── requirements.txt
└── orchestrationJava/
    ├── pom.xml              Maven (anthropic-java 3.8.0, JUnit 5, runnable -all.jar)
    └── src/main/java/com/example/orchestration/
        │   (interface                 ← implementation)
        ├── ResearchCoordinator  ← Coordinator               THE HUB: run(topic) (same steps as coordinator.py)
        ├── TopicDecomposer      ← LlmTopicDecomposer        1. decompose into subtopics
        ├── ResearchDelegator    ← ParallelResearchDelegator 2. send subtopics to the spokes, in parallel
        ├── WebSearchAgent       ← LlmWebSearchAgent            spoke 1 + its prompt builder
        ├── DocumentAnalysisAgent← LlmDocumentAnalysisAgent     spoke 2 + its prompt builder
        ├── DocumentLibrary      ← InMemoryDocumentLibrary      internal documents
        ├── CoverageEvaluator    ← LlmCoverageEvaluator      3. rate coverage, find gaps
        ├── ReportWriter         ← LlmReportWriter           4. final report + coverage table
        ├── MessageRouter        ← LoggingMessageRouter      every hub↔spoke message, in one place
        ├── Llm                  ← ClaudeLlm                 one Claude call = one fresh conversation
        ├── CoordinatorFactory.java  wires the implementations together (the only place that names them)
        ├── Subtopic, SubtopicResearch, ResearchReport, Document   data types
        ├── CoordinatorSupport.java  coordinator system prompt, JSON Schema builders, JSON parsing
        └── Main.java                entry point
```

## Architecture

```
                         ┌───────────────────────────┐
     topic ─────────────►│        COORDINATOR        │──────────► report + coverage table
                         │           (hub)           │
                         └─┬───────▲───────────┬───▲─┘
           prompt with ALL │       │ findings  │   │ findings
           needed context  │       │           │   │
                   ┌───────▼───────┴──┐    ┌───▼───┴───────────┐
                   │ web_search       │    │ document_analysis │
                   │ public sources   │    │ internal docs     │
                   └──────────────────┘    └───────────────────┘
                     no arrow between the spokes: they never talk to each other
```

### The workflow (one `run(topic)` call)

1. **Decompose.** The coordinator asks Claude for at least 5 subtopics that cover the *full breadth*
   of the topic, returned as structured JSON. If there are fewer than 5, or a required `must_cover`
   category is missing, that counts as **too narrow**, and it asks again.
2. **Delegate (in parallel).** For each subtopic:
   - The **web search** subagent gets a prompt with the topic, research goal, subtopic, scope and query.
   - The **document analysis** subagent runs **only if** a document keyword appears in the subtopic *name*.
     It gets those documents **plus the web findings, which the coordinator passes on**.
3. **Aggregate and evaluate.** The coordinator gathers all findings and asks Claude to rate each subtopic
   as `covered`, `partial` or `missing`, and to list important areas **no subtopic covers**.
4. **Refine.** It re-delegates **only the gaps**: partial subtopics get a targeted follow-up query plus
   the findings so far, and missing areas become new subtopics. Then it evaluates again, for up to 3 rounds.
5. **Report.** It writes the final report from the findings and appends a coverage table.

## How the code maps to the lesson

| Lesson concept | Where in the code |
|---|---|
| **Hub-and-spoke**: all communication goes through the coordinator | `_route()` / `route()` logs every message; a test checks the coordinator is on every edge |
| **Isolation**: subagents inherit nothing | every `LLM.complete` call is a new one-message conversation; the subagent system prompts never include the coordinator's |
| **Explicit context passing** | `build_web_search_prompt` / `LlmWebSearchAgent.buildPrompt` and the document-analysis equivalent |
| **No memory between invocations** | refinement rounds pass `prior_findings` back into the prompt |
| 1. Dynamic subagent selection | document analysis only when `find_documents()` returns something |
| 2. Scope partitioning | distinct subtopics, plus one source type per subagent (web vs. internal docs) |
| 3. Iterative refinement loop | `run()`: evaluate → re-delegate gaps → evaluate, up to `MAX_ROUNDS` |
| 4. Centralised routing and error handling | `_invoke()` / `invoke()`: one retry policy for every subagent call |
| **Narrow decomposition failure** | fewer than 5 subtopics, or a missed `must_cover` category, is sent back to Claude; the evaluator's `missing_areas` catches other breadth gaps |

### What happened on the first real run: the lesson's failure, live

The first version passed every offline test, and both real runs reported **12/12 subtopics covered**.
Yet the exercise's check failed: **fusion was missing** from both reports.

- Claude's decomposition treated fusion as "not renewable", so **no subagent was ever assigned it**.
- Every subagent did its job well, and the evaluator rated every *assigned* subtopic as covered.
- The gap was in **scope, not depth**, so the root cause was the **coordinator's decomposition**.

The fix went into the coordinator (`decompose()`), not into the subagents:

1. **Breadth prompt:** also include *borderline or adjacent categories that are commonly discussed alongside
   the topic, even if their classification is debated*.
2. **Required scope (`must_cover`):** the requester can name categories the report must cover. The
   coordinator checks the decomposition against them, asks Claude again naming what is missing, and as
   a last resort adds the subtopic itself.

After the fix, both runs passed 6/6. Python's decomposition produced
"Nuclear and fusion energy (debated classification)" and Java's produced
"Debated low-carbon and experimental sources".

### Exam takeaway: trace failures to where they started

If the report misses whole categories (scope, not depth), the problem is the **coordinator's
decomposition**, not the subagents. Each subagent researches exactly what it was assigned. That's
why this code checks breadth in two places: during decomposition (at least 5 subtopics, "full
breadth" in the prompt) and during evaluation (`missing_areas`).

## Python ↔ Java side by side

| Concept | Python | Java |
|---|---|---|
| Claude call | `ClaudeLLM.complete(system, prompt, schema)` | `ClaudeLlm.complete(system, prompt, schema)` |
| Structured output | `output_config={"format": {"type": "json_schema", "schema": ...}}` | `OutputConfig.builder().format(JsonOutputFormat...)` |
| JSON → objects | `json.loads` → dataclasses | Jackson → records (`Decomposition`, `Evaluation`) |
| Parallel delegation | `ThreadPoolExecutor` | `Executors.newFixedThreadPool` |
| Subagent context | keyword args to `build_*_prompt` | `WebSearchAgent.Context` / `DocumentAnalysisAgent.Context` records |

## Running

Both use your `ant auth login` sign-in (or `ANTHROPIC_API_KEY` if it is set). The default topic is
`renewable energy technologies`. For that topic the program passes solar, wind, geothermal, tidal,
biomass and fusion as `must_cover`, then checks that all six appear in the report.

Python:

```powershell
cd orchestrationPython
python coordinator.py
python coordinator.py "electric vehicle battery technologies"
python -m unittest -v test_coordinator      # offline, no API calls
```

Java (JDK 25 + Maven):

```powershell
cd orchestrationJava
mvn -q compile exec:java
mvn -q compile exec:java "-Dexec.args=electric vehicle battery technologies"
mvn test                                     # offline, no API calls
mvn clean package; java -jar target\orchestration-java-1.0.0-all.jar
```

A run makes about 15-25 calls to `claude-opus-5-5`, so it takes a few minutes and costs well under
a dollar. The web search subagent is a **stub**, like the search tool in `agentLoop`: it answers
from Claude's own knowledge, not live search results.
