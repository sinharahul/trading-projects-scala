# Project 2 — Trade Capture and Validation Engine (`trade-capture`)

Extracted verbatim from Part 3 of the book
(`trading-projects/parts-430/part-03-project2-trade-capture.md`).
Nothing was pushed to GitHub and the book sources were not modified.
**The code was not compiled (no Java/Scala toolchain in this VM).**

## What it does

Consumes FIX-like execution reports from the Kafka `executions` topic,
parses them, runs them through eight validation rules, enriches valid
trades from Postgres reference data (security master, counterparties,
trading calendar), writes validated trades idempotently to a Delta Lake
table (`validated_trades`) via a merge on `exec_id`, and routes rejects
to a Postgres `rejected_trades` table plus the `trade_dlq` Kafka topic.
A replay tool reprocesses corrected rejects.

## Prerequisites (from the book, Step 0)

- JDK 17 or 21, Scala 2.13, sbt 1.9.8 (pinned in
  `project/build.properties`).
- Spark 4.3.0 and Delta Lake 4.x — see the blocker below before building.
- Docker running Kafka (KRaft mode) and Postgres.
- Postgres schema/seed: run `src/main/resources/reference-data.sql`
  (schema first, then the seed rows) against the `backoffice` database.
- Configuration lives in `src/main/resources/application.conf`; the
  book's values point at `localhost:9092` (Kafka),
  `localhost:5432/backoffice` (Postgres) and `/tmp/delta-warehouse`.

## Build blocker — read before running `sbt`

`build.sbt` and `project/plugins.sbt` keep the book's Delta placeholder
**verbatim**: `"io.delta" %% "delta-spark" % "4.x"` and
`addSbtPlugin("io.delta" % "sbt-delta" % "4.x")`, each with the book's
comment telling the reader to substitute the exact patch from Delta
Lake's compatibility table for Spark 4.3.0. **No Delta patch version
was invented here, so the project will not resolve until that
placeholder is replaced with a real patch.** Do not copy a patch from
an older Spark version's table.

## How to run (from the book's "Running…" sections)

```bash
cd trade-capture
sbt compile

# Parser, rules, enricher, and idempotency suites (book's expected
# results: FixParserSpec 5/5, RulesSpec 16/16, EnricherSpec 5/5,
# IdempotencySpec 4/4):
sbt testOnly com.backoffice.capture.FixParserSpec
sbt testOnly com.backoffice.capture.RulesSpec
sbt testOnly com.backoffice.capture.EnricherSpec
sbt testOnly com.backoffice.capture.IdempotencySpec
sbt test   # full suite, final check in §3.17

# Consumer (the final Main.scala, Step 5.3 — an infinite polling loop;
# commit only after the Delta sink flushes):
sbt "runMain com.backoffice.capture.Main"
```

Note: the Step 1.3 parser demo (`Right(RawExecution(EXEC-99173, …))`)
used a *temporary* `Main.scala` that Step 5.3 explicitly replaces with
the consumer above. The temporary demo main is therefore **not** in
this extraction; the final `Main.scala` is the consumer.

### Replay (Step 4.3 / Step 7.4)

Corrections file format, one trade per line:

```text
EXEC-99173|31=227.45
```

```bash
sbt "runMain com.backoffice.capture.replay.ReplayTool <corrections-file> <config-path>"
```

See Extraction note 2 below: the book's Step 7.4 invocation shows only
one argument, but the extracted code requires two.

### Backfill reconciliation (Step 7)

Expected result for the book's 10,000-trade day (9,000 clean,
500 price outliers, 200 unknown symbols, 200 duplicates,
100 suspended-counterparty trades):

- Delta `validated_trades`: 9,000 booked.
- Postgres rejects by reason: `PRICE_OUT_OF_TOLERANCE` 500,
  `UNKNOWN_SYMBOL` 200, `DUPLICATE_EXEC_ID` 200,
  `COUNTERPARTY_NOT_APPROVED` 100.
- Identity: booked (9,000) + rejected (1,000) = received (10,000).
- After replaying the 500 price corrections: booked 9,500,
  rejected-and-not-replayed 500.

The sample-day generator (`/tmp/gen_day.py` in the book) and the
corrections-file generator are book-side development scripts, not
project sources, and are intentionally not included here.

## Extraction notes — gaps, ambiguities, and book inconsistencies

Nothing in the extracted code was invented, completed, or "fixed" to
resolve these. Each item below is reproduced exactly as the book wrote
it, and the item is flagged rather than silently corrected.

1. **Amendments vs. the duplicate rule (most important).** Step 2's
   `duplicateCheck` in `Rules.scala` rejects *any* already-booked
   `ExecID`, with no exemption for `ExecType` `TradeCorrect` (G).
   Step 5.2's `DeltaSink` merge, however, is built to *update* a row
   when `exec_type = 'TradeCorrect'`. Section 3.16A, Trace 3, states
   this combination is wrong: "If your implementation rejects
   amendments at validation, that is the bug, and the fix is to scope
   the duplicate rule to new fills" — but the book never shows a
   revised `Rules.scala`/`Validator.scala` implementing that scoping,
   and no amendment test exists in `RulesSpec`. As extracted, an
   amendment for a booked ExecID will be rejected at validation with
   `DUPLICATE_EXEC_ID` and will never reach the merge. No fix was
   applied here.
2. **ReplayTool checksum seam (book-documented).** `ReplayTool`
   `applyCorrections` drops tag `10` and its own comment says the
   parser's checksum step is bypassed by dropping it — but the
   extracted `FixParser` rejects a message with a missing checksum
   (`"Missing checksum tag: 10"`). The book says the worked example
   recomputes the checksum and leaves that recomputation as an
   exercise. As extracted, replay will fail at parse after correction
   unless that exercise is implemented. Related: `ReplayTool.main`
   requires two arguments (`<corrections-file> <config-path>`), yet
   Step 7.4's invocation passes only the corrections file, and the
   second argument is never actually used — the JDBC values are
   hardcoded in the tool.
3. **Replay duplicate gap (book-documented, Exercise 4).**
   `ReplayTool` builds its `ValidationContext` with
   `existingExecIds = Set.empty`, so a replayed duplicate of an
   already-booked trade is not caught by the duplicate rule. The book
   says to load booked ExecIDs from Delta (reusing the consumer's
   `bookedExecIds` helper); that change is not in the book's listing
   and was not added here.
4. **DeltaSink merge-count arithmetic.** `mergeClean` derives
   `inserts = clean.size - duplicates` and then
   `updated = clean.size - inserts - duplicates`, which is always 0 —
   even for a `TradeCorrect` amendment that the merge does update.
   The book's own comment admits counts are derived from batch
   composition rather than Delta's operation metrics. The
   `IdempotencySpec` amendment test checks row count and price, not
   the returned counts, so this does not fail the book's tests, but
   the returned `(inserted, updated, skipped)` tuple misreports
   updates as 0. Also, conflict rows are counted in the "skipped"
   slot (see the `mergeBatch` comment), which the caller must handle
   via the DLQ — `mergeBatch` itself does not write conflicts to the
   DLQ.
5. **Sequence-gap detector: path inferred, wiring not included.**
   Section 3.14.2 gives a complete `SequenceGapDetector` class in
   package `com.backoffice.capture.validation`, but the book never
   gives it a file path and it is absent from the §3.2.4 directory
   layout. It is included here as
   `validation/SequenceGapDetector.scala` (the only path consistent
   with its package). The §3.14.3 consumer-loop wiring snippet
   (creating the detector, extracting tag 34, logging gaps) is a
   fragment of the Step 5.3 consumer, not a complete file, and the
   final `Main.scala` predates it — so that wiring was **not** merged
   into `Main.scala`. The §3.14.5 end-of-day reconciliation Spark
   snippet is likewise a fragment, not a project file, and was left
   out. The §3.14.4 late-arrival flag (`lateArrival` on
   `EnrichedTrade`, plus a Delta schema column) is an exercise only —
   it is not in any listing and was not implemented.
6. **DeadLetterQueue JSON escaping (book-documented, Exercise 3).**
   The Kafka payload is built by string interpolation with double
   quotes replaced by single quotes. The book states this is
   acceptable only for controlled sample data and unacceptable for
   production data containing quotes/backslashes; a real JSON library
   is the exercise. Kept verbatim.
7. **ReferenceDataReader / consumer simplifications (book-noted).**
   `DriverManager` connections per load (no pool), a five-minute
   snapshot refresh, a per-batch full-table `bookedExecIds` scan in
   `Main.scala` (the book flags it as a bottleneck at scale), and a
   plaintext database password in `application.conf` (acceptable for
   the book's local project only; production must use a secret
   manager). All kept verbatim.
8. **Validator verbatim quirks.** `Validator.validate` computes
   `val info = ctx.securities(raw.symbol)` and never uses it, and
   imports `java.time.LocalDate` without using it. Both are in the
   book's listing and were kept, not cleaned up.
9. **Step 7.4 prose vs. corrections file.** The prose says the
   corrected price for the fat-finger trades is 45.231, but the
   corrections file the book generates writes `31=45.23`. This
   extraction does not include the generator, so no value was chosen;
   the discrepancy is noted here for whoever runs the backfill.
10. **Version note (Part 1 vs. Part 3).** Part 3's `build.sbt` pins
    `logback-classic` 1.4.14 and adds `scala-logging` 3.9.5; Part 1's
    version table lists Logback 1.4.11 and no scala-logging. Part 3's
    file was copied verbatim as instructed (the project's own "Build
    File" section takes precedence); the difference was not
    reconciled.
11. **Files referenced but never shown as listings.** No complete
    listing is given for a `.gitignore`, a Kafka producer script for
    the backfill (Step 7.2's console-producer invocation is noted in
    the book itself as needing a key-setting producer script), or any
    metrics-logging addition to the consumer loop (§6.3 describes
    logging `metrics.snapshot` periodically but shows no code for
    it). None was invented.
12. **Exercises not implemented.** Exercises 1–5 (§3.12: tick-size
    rule, `TradeCancel` handling, JSON library, replay duplicate gap,
    reference-data sanity check) and the §3.14.4 late-arrival exercise
    are proposals in the book, not listings. None is implemented here.

## Files

See the directory tree for the full list; line counts are in the
delivery report. Main sources are under
`src/main/scala/com/backoffice/capture/` (`model/`, `parsing/`,
`validation/`, `enrichment/`, `sink/`, `metrics/`, `replay/`), tests
under `src/test/scala/com/backoffice/capture/`, plus `build.sbt`,
`project/build.properties`, `project/plugins.sbt`, and
`src/main/resources/` (`application.conf`, `reference-data.sql`).
