# Corporate Actions Processor — Project 4

Extracted from Part 5 (Project 4: Corporate Actions Processor) of the
Spark 4.3.0 edition of the trading back-office book
(`trading-projects/parts-430/part-05-project4-corporate-actions.md`).
The book sources were not modified, and nothing was pushed to GitHub.

## What this is

A corporate actions processor: it models seven action types (split,
reverse split, cash dividend, stock dividend, merger, rights issue,
spin-off), adjusts positions and cost basis, computes dividend
entitlements by record date / ex-date, converts positions through
mergers, allocates and exercises rights, allocates spin-off cost basis
by fair market value, resolves effective / ex / record / payment dates
and backdating, writes an audit trail, and applies each action version
exactly once (idempotency, with a correction path).

## How to run it

From this directory, as given in Part 5:

```bash
sbt test
```

```bash
sbt "runMain com.backoffice.corporateactions.Main"
```

Expected demo output (Part 5, "The Demo Application"):

```
after split: 200 @ 25.00
dividend: ACC-1 entitled 200 = 150.00
audit records: 1
  CA-2026-1042: 100 -> 200, basis 50.00 -> 25.00
```

Prerequisites, per Part 5 / Part 1: Scala 2.13.12, Spark 4.3.0
(the `spark-sql` dependency here is `% "provided"`, so the core engine
is pure Scala and testable without a cluster; see the book's note that
the Spark dependency is optional if you run the core engine without
Spark). If dependency resolution fails, Part 5 advises checking that
the Part 1 environment has the Spark and Scala versions above.

## Layout

```
trading-backoffice/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── README.md
└── src/
    ├── main/scala/com/backoffice/corporateactions/
    │   ├── model.scala
    │   ├── adjustments.scala
    │   ├── entitlements.scala
    │   ├── dates.scala
    │   ├── audit.scala
    │   ├── engine.scala
    │   └── Main.scala
    └── test/scala/com/backoffice/corporateactions/
        └── CorporateActionsSpec.scala
```

## Extraction notes — read before treating this as complete

- **No `Create …` markers in Part 5.** Unlike the task's example
  marker, Part 5 does not label each listing with a target file. The
  file names above are taken verbatim from Part 5's "Project Layout"
  section, and the listings were grouped into them as follows:
  - `model.scala` — Step 1: event model, `Position`/`Trade`,
    `CorporateActionValidator`, `CorporateActionParser`.
  - `adjustments.scala` — Step 2 `SplitAdjuster` (with
    `FractionalTreatment` / `AdjustedPosition`), Step 4
    `MergerEngine`, Step 5 `StockDividendAdjuster`, Appendix A
    `SpinOffEngine`.
  - `entitlements.scala` — Step 3 `DividendEngine`, Step 5
    `RightsEngine`.
  - `dates.scala` — Step 6 `DateEngine`.
  - `audit.scala` — Step 7 `AuditWriter` / `AuditQuery`.
  - `engine.scala` — Step 8 `AppliedActionRegistry`,
    `IdempotentEngine`, `CorrectionEngine`, Appendix D `BatchRunner`.
  - `Main.scala` — "The Demo Application".
  - `CorporateActionsSpec.scala` — "Test Suite", verbatim.
  This grouping is an editorial mapping, not a mapping printed in
  the book. If you prefer one file per listing, split the files at
  the blank-line-separated top-level definitions; no code needs to
  change to do so.
- **No incremental-file ambiguity was found.** No file is shown in
  a basic version and then extended by a later listing, and no
  "change the signature to:" edit occurs in Part 5. Each engine is
  shown once, complete. The one reuse is explicit in the book:
  `StockDividendAdjuster` delegates to `SplitAdjuster` with the
  action type relabelled to `Split`.
- **Listings without a package line.** The book prints a `package
  com.backoffice.corporateactions` line on the first listing of
  most steps, but not on: `Position`/`Trade`, the validator, the
  parser, `StockDividendAdjuster`, `AuditQuery`,
  `CorrectionEngine`, and the Appendix A `SpinOffEngine`. All were
  placed under the same package (matching their directory path).
  This is an assumption, flagged here rather than silently made.
  Duplicate package lines from later listings in the same file were
  removed; no other code was altered.
- **"Try It" snippets were NOT extracted.** The short snippets in
  Steps 1–5 and Step 8 (parse a split, split a small book, run the
  dividend example, convert a small book, allocate/exercise rights,
  apply a split three times) are REPL/demo fragments, not presented
  as file content, so they are not files here. The demo `Main`
  covers the same split + dividend flow. Re-running a "Try It"
  snippet requires wrapping it in an object or the REPL.
- **Appendix B was NOT extracted as code.** Its Delta SQL table
  definitions and its 6-line Spark `withColumn` split illustration
  are a production-translation sketch, not complete files, so they
  are left out rather than completed or invented.
- **Exercises 1–5 are NOT implemented.** They are given as hints
  only (spin-off end-to-end beyond Appendix A's engine, tender
  offer with proration, election merger, lot-level adjustments,
  restatement replay). No code for them exists in Part 5, so none
  was invented here. Note in particular: the book's position model
  is single-lot; lot-level processing (Exercise 4) is explicitly
  left as an exercise.
- **Merger worked-example error — in the book, not in the code.**
  Part 5 itself flags that its first printed expected output for
  ACC-3 in the mixed-merger "Try It" (166 GLOBEX @ 20.50) is wrong
  and that the correct value is 166 @ 5.0151. The extracted
  `MergerEngine` code is unchanged; the test suite asserts only the
  ACC-1 case (500 @ 20.00), so this discrepancy is not covered by
  a test here.
- **Demo ordering caveat (book's own note).** `Main` applies the
  split before computing the dividend, so the demo dividend is on
  the post-split quantity (200 × 0.75 = 150.00). Part 5 states this
  is a demo simplification, not a rule: in production each action's
  entitlement is computed on the position as of its own record
  date (see Step 6 / Appendix A2 on overlapping actions).
- **`build.sbt` is verbatim from Part 5's "build.sbt" section.**
  It contains Scala 2.13.12, organisation `com.backoffice`, project
  name `corporate-actions-processor`, `spark-sql` 4.3.0 `% "provided"`,
  and ScalaTest 3.2.17 `% Test`. It contains **no Delta Lake
  dependency and no Delta "4.x" placeholder** — that placeholder
  discussion belongs to Part 1's shared template, not to this
  project's own build file, so none was added.
- **`project/build.properties` is from Part 1, not Part 5.**
  Part 5's layout lists `project/build.properties` but does not
  print its content. Part 1, Step 7.2 pins `sbt.version=1.9.8`
  for every project in the book, and that value is used here.
- **`project/plugins.sbt` is comment-only.** Part 5's layout does
  not list a `plugins.sbt` at all, and Part 1, Section 1.23.5
  names only sbt-assembly (Project 3) and sbt-scalafmt
  (Project 10) with no coordinates or versions. No plugin version
  was invented; add a plugin here only when a project step
  demonstrates the need for it.
- **Not compiled.** The code was **not compiled** in this extraction:
  no Java/Scala toolchain is available in this VM. Before use,
  run `sbt test` in an environment with JDK 17 or 21, Scala 2.13.12
  and sbt 1.9.8. Brace/paren/bracket balance, absence of markdown
  fences, and package-line/directory agreement were checked
  mechanically for every `.scala` file (see the extraction report).
