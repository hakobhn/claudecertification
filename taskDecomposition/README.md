# Task Decomposition — Build Exercise 1.6

A multi-pass code review pipeline compared with a single-pass review, built twice (Python and Java),
for lesson 1.6
(https://aicertguides.com/learn/1-agentic-architecture/1-6-task-decomposition).

**Read `review.py` / `Review.java` first.** That file holds both architectures side by side.

```
taskDecomposition/
├── sample-pr/                 14 JS files from a made-up bookshop checkout, with planted bugs
├── taskDecompositionPython/
│   ├── review.py              THE LESSON: single_pass, local_pass, integration_pass, multi_pass
│   ├── compare.py             the planted bugs, scoring and the comparison table
│   ├── llm.py                 one Messages API call with structured output
│   ├── main.py                runs both architectures and prints the comparison
│   └── test_review.py         offline tests (fake LLM)
└── taskDecompositionJava/src/main/java/com/example/review/
    ├── Review.java            same as review.py
    ├── Compare.java           same as compare.py
    ├── Llm.java               same as llm.py
    └── Main.java              same as main.py
```

## The one picture to remember

```
SINGLE PASS                              MULTI-PASS (a fixed pipeline / prompt chaining)

┌────────────────────────┐               file 1  ─► local pass ─┐  each file gets the
│ all 14 files in ONE    │               file 2  ─► local pass ─┤  model's full attention
│ prompt                 │               ...                    │
│                        │               file 14 ─► local pass ─┤
│ attention is shared by │                                      ▼
│ 14 files: later files  │                          integration pass  ◄─ sees the per-file
│ get skimmed            │                          (cross-file only)    summaries, NOT the code
└────────────────────────┘                                      │
                                                                ▼
                                                             report
```

- **Layer 1, local passes:** one call per file. The call gets only that file, so it catches local bugs.
  It also lists the file's *interface*: every export and import, with parameters, return value and units.
- **Layer 2, integration pass:** one call that sees every file's findings and interface, but not the
  source. It looks *between* files for three things. **Data flow:** units, types and nulls that change
  across a call. **API consistency:** imports that don't match exports. **Pattern consistency:** the
  same code judged differently in different files.

**The fix for attention dilution is structural.** A bigger model, a bigger context window or a
stronger prompt still puts every file into one pass. Batching files into groups helps within each
batch, but **without an integration pass it misses cross-batch issues**.

## Which pattern, when

| Task | Pattern | Why |
|---|---|---|
| Multi-file code review (this exercise) | **Fixed pipeline** | The steps are known in advance, so you get consistent, debuggable output |
| Document extraction, compliance checks | **Fixed pipeline** | The fields and format are predetermined |
| Adding tests to unknown legacy code | **Dynamic decomposition** | Dependencies only show up as you go, and the plan must change |
| Debugging an unfamiliar system | **Dynamic decomposition** | The root cause is unknown, so the investigation must adapt |

The multi-pass review here is a fixed pipeline: it runs 14 local passes and then 1 integration pass,
always. It never adds a step based on what a step found. That is the right choice for a review. Lesson
1.2's coordinator, which adds subtopics when it finds a gap, is an example of dynamic decomposition.

## The planted issues (`sample-pr/`)

| Kind | Where | Issue |
|---|---|---|
| local | 03-orders.js:7 | `getOrder` has no sign-in or ownership check |
| local | 06-inventory.js:12 | off by one: `requested <= available + 1` |
| local | 07-catalog.js:11 | SQL injection through the search term |
| local | 08-shipping.js:8 | `order.address.country` fails on ebook orders, whose address is null |
| local | 10-reviews.js:11 | SQL injection through the review text |
| local | 11-wishlist.js:7 | `getWishlist` has no sign-in check; the code is **identical** to 03 |
| local | 12-invoices.js:20 | `order.customer.email` fails on guest orders, whose customer is null |
| local | 13-tax.js:8 | the VAT rate is a percentage but is never divided by 100 |
| cross-file | 04 ↔ 05 | `applyDiscount` returns **dollars**; `computeTotal` stores it as **cents** |
| cross-file | 14 ↔ 04 | receipts import `calculateTotal`; the basket exports `computeTotal` |
| pattern pair | 03 ↔ 11 | identical `forEach` + `push` loop: flagging one copy and not the other is a dilution artefact |

## What the real runs showed (claude-opus-5-5)

| | Python single | Python multi | Java single | Java multi |
|---|---|---|---|---|
| API calls | 1 | 14 + 1 | 1 | 14 + 1 |
| Time | 19 s | 60 s | 21 s | 61 s |
| **Total findings** | **15** | **83** | **16** | **92** |
| Files with 0 findings | 2 (01, 02) | 0 | 2 (01, 02) | 0 |
| Planted local bugs caught | 8/8 | 8/8 | 8/8 | 8/8 |
| Planted cross-file issues caught | 2/2 | 2/2 | 2/2 | 2/2 |
| Pattern-pair artefacts | none | none | none | none |

**What this shows, honestly:**

- **Depth diluted; recall did not.** The planted bugs are textbook bugs (SQL injection, an obvious
  null), and a current model found all 8 even in one pass. But the single pass gave about **1
  finding per file**, against 5 to 8 per file with dedicated passes. It said nothing at all about
  files 01 and 02. That is the lesson's first symptom, shallow analysis, in its "every file gets
  skimmed" form. It is not the classic "early files detailed, late files missed" form.
- **The multi-pass depth was even.** The last file got as much attention as the first: 14-receipts.js
  got 6 to 7 findings and 01-auth.js got 5.
- **The integration pass found what no local pass could.** It found both planted cross-file issues.
  It also found downstream effects: the dollars-as-cents total is charged 100 times too low in
  09-payments.js and defeats the free-shipping threshold in 08-shipping.js. And it found
  **severity inconsistencies among the local passes themselves**: the same missing-ownership check
  was rated *critical* in 03 and *high* in 11. That is pattern-consistency checking at work.
- To see recall fail too, use more files, longer files, or subtler bugs. The 14-file exam example
  shows the full pattern. The structure that fixes it is the same.

## Running

```powershell
cd taskDecompositionPython
python main.py                    # reviews ../sample-pr both ways; writes results.json
python main.py C:\path\to\src     # any folder with 10+ .js/.ts files
python -m unittest -v test_review
```

```powershell
cd taskDecompositionJava
mvn -q compile exec:java                                    # or "-Dexec.args=C:\path\to\src"
mvn test
```

Each run makes 16 API calls. `results.json`, which holds every finding from both architectures, is
git-ignored.
