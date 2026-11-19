# Session State and Resumption — Build Exercise 1.7

Resume, fork, and a fresh start with summary injection, built twice (Python and Java), for lesson 1.7
(https://aicertguides.com/learn/1-agentic-architecture/1-7-session-state-resumption).

**Read `sessions.py` / `Sessions.java` first.** That file holds the three options in about 40 lines.

```
sessionResumption/
├── codebase/
│   ├── original/              a hotel's room booking service: 10 JS files
│   └── fixes/                 the 3 core files after the developer's overnight fixes
├── sessionResumptionPython/
│   ├── sessions.py            THE LESSON: start, resume, fork, summary_injection_prompt
│   ├── agent.py               lesson 1.1's loop with list_files / read_file; saves the session
│   ├── main.py                day 1 -> fork -> overnight fixes -> day 2 (resume vs fresh) -> comparison
│   └── test_sessions.py       offline tests (scripted fake model)
└── sessionResumptionJava/src/main/java/com/example/sessions/
    ├── Sessions.java          same as sessions.py
    ├── Agent.java             same as agent.py (+ converts saved JSON <-> SDK messages)
    └── Main.java              same as main.py
```

A session is a named history saved to `sessions/<name>.json`, in the same JSON shape in both
languages. Open one after a run: you will see every `tool_result`, including the full text of every
file the agent read. **That is the state you carry forward when you resume.**

## The one picture to remember

```
day 1:  booking-review  [read 10 files ... 11 tool results ... summary]
                │
   overnight:   │  auth.js, database.js, api-routes.js are fixed on disk
                │
day 2:          ├── resume ─────► SAME history + new question
                │                 the 11 old tool results come back, including the OLD auth.js,
                │                 database.js and api-routes.js
                │
                ├── fork ───────► a COPY of the history, changed independently
                │                 for comparing approaches; it inherits the stale results too
                │
                └── fresh + summary ► NEW history; the first message is the curated summary
                                      plus "these 3 files changed: re-read only those"
                                      no stale results; prior knowledge kept
```

| Situation | Use | Why |
|---|---|---|
| Nothing changed since yesterday | `--resume` | The history is still true and the full context is useful |
| Comparing two refactoring strategies | `fork_session` | Both branches start from the same analysis and can't see each other |
| The files that most of the analysis relied on have changed | **fresh start + summary** | The old tool results would contradict the files on disk |
| A long session cluttered with irrelevant tool results | **fresh start + summary** | A clean baseline that keeps the conclusions |
| Continuing one line of work | **not** `fork_session` | A fork splits one piece of work across sessions |
| Stale files | **not** `fork_session` | A fork inherits the stale history |

**Targeted re-analysis:** the fresh session re-reads only the 3 files that changed. The summary covers
the other 7, so re-reading all 10 would be wasted work.

## The steps (`main.py` / `Main.java`)

1. **analyse:** a named session (`booking-review`) reads all 10 files. Its final answer is a
   **structured summary** (file, line, severity, description, recommendation) from structured output.
   That summary is saved to `summary.json`, ready to inject later.
2. **fork:** two branches from day 1 plan different refactors (a repository layer, or Express
   middleware). The original session is left unchanged.
3. **modify:** the developer's fixes overwrite `auth.js`, `database.js` and `api-routes.js`.
4. **resume (the trap):** the session is resumed with "Good morning, I'm back. Which bugs are still
   open?". The developer doesn't mention the changes.
5. **fresh (the fix):** a new session gets the summary plus the list of changed files.
6. The two day-2 answers are compared.

The comparison counts a **stale claim** as any report that one of the 3 bugs fixed overnight is
still open (auth.js:16, database.js:17, api-routes.js:25). Other open issues in those files are real,
so they don't count.

## What the real runs showed (claude-opus-5-5)

Day 1 found the 3 planted core bugs plus the 2 that nobody fixes (bookings.js overlap check and
pricing.js missing Friday), along with several minor issues. Both forks kept the original at
6 messages.

| Day 2 | Old tool results in history | Files read | Stale claims | Still-open bugs kept | Fixes verified |
|---|---|---|---|---|---|
| Python, resume | 11 | 8 | 0 | 2/2 | 3 |
| Python, fresh + summary | **0** | **3** | 0 | 2/2 | 3 |
| Java, resume | 11 | 10 | 0 | 2/2 | 3 |
| Java, fresh + summary | **0** | **3** | 0 | 2/2 | 3 |

**What this shows, honestly:**

- **The model avoided the trap on its own.** On resume, it re-read the files before answering, 8 or
  10 of them, every time, so it made no stale claims. That is a good habit. But it is exactly the
  "resume and re-read" workaround the lesson calls *better than nothing*. The 11 old tool results,
  including the unfixed `auth.js`, stay in the history next to the new ones. You are relying on the
  model to prefer the newer copy every time, for every later question.
- **The fresh session was correct by construction.** It had no old tool results to prefer or
  ignore. It read only the 3 changed files, which is targeted re-analysis instead of full
  re-exploration. It confirmed the 3 fixes and still knew about the 2 open bugs in files it never
  re-read, because they were in the summary. Its answer marked those bugs "from the earlier
  review, not re-read". That is the summary doing its job.
- The cost difference is real even here: 3 file reads instead of 8–10, and a much shorter context.
  With a 50-file codebase the gap grows.

## Claude Code and the Agent SDK

This exercise saves sessions itself so that the mechanism is visible. The real tools do the same
thing:

```bash
claude --name booking-review             # day 1: a named session (or /rename mid-session)
claude --resume booking-review           # continue it: the full history comes back
claude --resume booking-review --fork-session   # branch it: new session, copied history
claude                                   # fresh start: paste the summary + changed files
```

In the Agent SDK, `resume=<session id>` continues a session, and adding `fork_session=True` starts a
new session from a copy of it. Docs: https://code.claude.com/docs/en/agent-sdk/sessions

## Running

```powershell
cd sessionResumptionPython
python main.py                 # everything: analyse, fork, modify, resume, fresh, comparison
python main.py analyse         # or one step: fork | modify | resume | fresh
python -m unittest -v test_sessions
```

```powershell
cd sessionResumptionJava
mvn -q compile exec:java                       # or "-Dexec.args=resume"
mvn test
```

`workspace/` (the copy of the codebase that gets modified) and `sessions/` are created by the run and
are git-ignored. Run `analyse` again to reset the workspace.
