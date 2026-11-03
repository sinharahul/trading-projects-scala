# Project 3 — Positions and P&L (`positions-pnl`)

Extracted verbatim from Part 4 of the book (Spark 4.3.0 edition):
`trading-projects/parts-430/part-04-project3-positions-pnl.md`.
Do not edit the book sources; this directory is the extracted copy.

Stack (from the book): JDK 17 or 21, Scala 2.13.12, sbt, Spark 4.3.0
with Structured Streaming, Delta Lake 4.x, Kafka, ScalaTest.
Delta Lake: the `build.sbt` below keeps the book's `"4.x"` placeholder
verbatim — substitute the exact patch from Delta Lake's compatibility
table for Spark 4.3.0 before building. **No Delta patch version has
been invented here.**

## How to run it (from the book)

Prerequisites (book §4, intro and Step 1): the Part 1 shared template
builds with `sbt compile`; Kafka and Postgres run via
`docker compose up -d`; validated trades are on the Kafka topic
`trades.validated` (Project 2's output, or the Project 1 sample trade
generator replayed into that topic).

```bash
sbt compile
sbt test

# Individual suites, as given in the book:
sbt "testOnly positions.ModelSpec"
sbt "testOnly positions.PositionEngineSpec"
sbt "testOnly positions.FifoSpec"
sbt "testOnly positions.RealizedPnlSpec"
sbt "testOnly positions.MarkToMarketSpec"
sbt "testOnly positions.ShortSaleSpec"
sbt "testOnly positions.FullDaySpec"
sbt "testOnly positions.AmendmentSpec"
```

Streaming job (Terminal 2; Terminal 1 runs the sample trade generator
against `trades.validated`, book §4.4 Step 2.5 / §4.17.3):

```bash
./scripts/generate-trades.sh --topic trades.validated --rate 5
sbt "runMain positions.PositionStreamingJob"
```

Checkpointing/recovery (book Step 2.6): stop the job with Ctrl-C and
restart it; state is restored from `/tmp/checkpoints/positions-pnl`.
Do not delete that checkpoint directory between runs.

Snapshots, breaks, backfill (book Steps 6–7, §4.16.3, §4.17.4):

```bash
# Intraday snapshot, every 15 minutes during market hours (example cron)
*/15 9-16 * * 1-5 sbt "runMain positions.SnapshotJob INTRADAY"
# End-of-day snapshot, after the close
30 16 * * 1-5 sbt "runMain positions.SnapshotJob EOD"

# Break detection for a date (default in code: 2026-10-30)
sbt "runMain positions.BreakDetectionJob 2026-10-30"

# Batch backfill: recompute positions from full trade history
sbt "runMain positions.BackfillJob"
```

Supporting infrastructure (book §4.17.1–4.17.2):

```bash
docker compose up -d
docker compose ps
kafka-topics --bootstrap-server localhost:9092 --create \
  --topic trades.validated --partitions 4 --replication-factor 1
```

Sample data and expected outputs: see book §4.27 (sample validated
trades as Kafka JSON on `trades.validated`, sample latest prices CSV,
sample custodian file with one deliberate break, expected final console
rows and expected end-of-day snapshot for ACC-1001/XYZ). The sample
data files themselves are not copied into this directory — they are
only described in the book, not given as project files.

Acceptance checklist (book §4.17.5): all unit tests pass; the canonical
worked example reproduces exactly; FIFO and average cost agree on fully
closed positions; the streaming job survives a restart with state
intact; a snapshot round-trips against hand computation for one long
and one short position; break detection finds a deliberate break and
only that break; the full-day test passes.

## Extraction notes — gaps and ambiguities (read before building)

1. **Project directory name is inferred, not found in Part 4.** Part 4
   contains no "Directory Layout" section. This directory is named
   `positions-pnl` from Part 4 Step 1.1, where the build file sets
   `name := "positions-pnl"` (and the streaming app is
   `positions-pnl-streaming`). If the book's intended directory name
   differs, rename this folder; no code depends on the folder name
   except by convention.
2. **`project/plugins.sbt` has no active plugin line.** Part 1 §1.23.5
   names sbt-assembly as the plugin introduced in Project 3, but
   neither Part 1 nor Part 4 states its version, so no version was
   invented. Add an authoritative version before using `assembly`.
   Plain compile/test/runMain do not need it.
3. **Source file names are inferred.** Part 4 never names the `.scala`
   files (no `Create src/main/scala/...` markers). Each complete
   listing was written as one file named after its principal type
   (e.g. `PositionEngine.scala`, `FifoMethod.scala`), and the domain
   model listing (Side, Trade, Lot, PositionState, PositionSnapshot,
   PositionBreak) was kept intact as `Model.scala`. Package
   declarations are verbatim (`package positions`) and match the
   directory path in every file.
4. **`build.sbt` is an excerpt, copied verbatim.** Part 4 labels it
   "build.sbt (project3 excerpt)". It lists only spark-sql,
   spark-sql-kafka-0-10, delta-spark `4.x`, and ScalaTest — although
   the surrounding prose says the project adds "the same dependencies
   as Project 2 … plus ScalaTest". It does not list kafka-clients,
   spark-streaming, Postgres, Config, or logging. Nothing was added
   to fill those gaps.
5. **No incremental-file merges were needed.** No file is shown in
   Part 4 in a basic version and later extended to a different final
   version, so every file here is the single complete listing the book
   shows for it — except for the two partial excerpts in item 6, which
   were deliberately not turned into files or edits.
6. **Partial listings left out (not invented, not completed):**
   - Step 2.7 state-TTL snippet: a commented fragment showing how to
     replace `NoTimeout` with an event-time timeout via a
     `POSITION_STATE_TIMEOUT` environment variable. It is not a
     complete file and the timeout branch is never shown, so
     `PositionStreamingJob.scala` keeps the book's `NoTimeout`
     version verbatim. TTL is unimplemented in the extracted code.
   - Step 7.4 break-report query: a bare `val report = spark.sql(...)`
     fragment with no enclosing object, session, or file. Left out;
     the SQL itself is in the book (§4.19 gives the full reporting
     queries as Spark SQL, also left out as files — they are queries,
     not project sources).
7. **Runbook step with no supporting code — `DRY_RUN`.** §4.17.2 says
   to create the Delta tables by running
   `sbt "runMain positions.SnapshotJob DRY_RUN"`, but the `SnapshotJob`
   listing has no `DRY_RUN` handling (any argument is treated as the
   snapshot type). Following that runbook step literally would write
   a snapshot of type `DRY_RUN`. This was not "fixed" — it is flagged
   here.
8. **Stale-price query needs a column the model does not have.**
   §4.19.3's query filters on a `priceTime` column and the book tells
   the reader to "add it to `PositionSnapshot`", but no amended
   `PositionSnapshot` listing is ever shown. `Model.scala` therefore
   keeps the original `PositionSnapshot` without `priceTime`, and
   that query cannot run against the extracted model as-is.
9. **`PositionPropertySpec` needs dependencies the build file lacks.**
   The property-test listing imports ScalaCheck and
   `org.scalatestplus.scalacheck`, but the verbatim `build.sbt`
   excerpt declares no ScalaCheck / ScalaTestPlus dependency. The spec
   was extracted verbatim and flagged here rather than adding a
   dependency version the book does not state for this project.
10. **Other book-described behaviours with no code listing:** the
    idempotency/`seenTradeIds` set (Pitfall 5, described as "add … for
    local testing, or better, deduplicate upstream" — neither
    implemented in any listing), the end-of-day completeness check
    (Step 6.3, described, not coded), break aging/escalation queries
    (Step 7.5, described as queries, not shown), and the
    multi-currency / per-desk / restatement / audit-trail extensions
    (Exercises, hints only). None of these were invented.
11. **SnapshotJob as written collects twice and drops FIFO lots.**
    This is the book's code, kept verbatim, not a transcription error:
    `SnapshotJob` calls `joined.collect()` once for snapshots and
    again for unpriced positions, and `PositionRow.toState` rebuilds
    state with `lots = Nil` (the book notes lots are "stored separately
    for audit"). Flagged so a reviewer does not mistake it for an
    extraction mistake.
12. **Not compiled.** No Java/Scala toolchain is available in this VM,
    so the code was verified only by extraction checks (verbatim
    listings, balanced delimiters, package/directory match, no
    Markdown fences or prose leaked in). It has not been compiled or
    tested. The Delta `4.x` placeholder and the missing plugin version
    (items 2 and 4) must be resolved before a build can succeed.

## Files

- `build.sbt`, `project/build.properties` (`sbt.version=1.9.8`, from
  Part 1 Step 7.2), `project/plugins.sbt` (see item 2 above)
- `src/main/scala/positions/`: Money, Model, PositionEngine,
  PositionStreamingJob, TradeSchema, CostBasis, FifoMethod,
  CorporateActions, CostBasisConfig, MarkToMarket, SnapshotJob,
  PositionRow, BreakDetectionJob, BackfillJob, VersionedTrades
- `src/test/scala/positions/`: ModelSpec, PositionEngineSpec, FifoSpec,
  RealizedPnlSpec, MarkToMarketSpec, ShortSaleSpec, FullDaySpec,
  PositionPropertySpec, AmendmentSpec
