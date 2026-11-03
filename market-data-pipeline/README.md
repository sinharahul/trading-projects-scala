# Market Data Ingestion Pipeline — Project 1

Extracted Scala code from Part 2 (Project 1: Market Data Ingestion Pipeline) of the
book. The book source was read only and was not modified. Nothing was pushed to
GitHub as part of this extraction.

**Status: NOT COMPILED.** No Java/Scala toolchain is available in the extraction
environment, so this code has not been compiled or run here. Before compiling,
see the Delta Lake note below.

## What it builds

A Kafka → Spark Structured Streaming → Delta Lake pipeline:

- A generator produces realistic trade/quote ticks (with a configurable defect
  rate for duplicates and late events).
- `KafkaProducerApp` sends them to Kafka topic `market-data-raw`, keyed by symbol.
- `MarketDataStreamingJob` lands raw records in the Delta bronze table, exactly
  as received.
- `SilverStreamingJob` parses bronze as a stream, validates records, writes
  invalid records to a quarantine table with a reason, and writes deduplicated
  valid records to the silver table.
- `BarBuilderJob` builds one-minute OHLCV bars from silver trades only.
- `PipelineMonitor` reports bronze/silver/quarantine/bars row counts and the
  reconciliation arithmetic.

## Prerequisites (from the book)

- JDK 17 or 21, sbt, and Docker containers running Kafka and PostgreSQL.
- Before compiling, replace the `4.x` Delta placeholder in `build.sbt` with the
  exact patch from Delta Lake's compatibility table for Spark 4.3.0. **Do not
  guess a patch number** — the book is explicit about this, and the placeholder
  is preserved verbatim in `build.sbt`.

## Kafka topic setup

```bash
docker exec kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic market-data-raw \
  --partitions 3 --replication-factor 1 \
  --config retention.ms=604800000
```

## Running it (from the book)

Compile the empty skeleton first, then run the jobs in dependency order:

```bash
sbt compile

# Terminal 1 — bronze job
sbt "runMain com.backoffice.marketdata.streaming.MarketDataStreamingJob"

# Terminal 2 — silver + quarantine job
sbt "runMain com.backoffice.marketdata.streaming.SilverStreamingJob"

# Terminal 3 — bar builder
sbt "runMain com.backoffice.marketdata.bars.BarBuilderJob"

# Terminal 4 — producer: 2000 base ticks, 2% defect rate
sbt "runMain com.backoffice.marketdata.generator.KafkaProducerApp localhost:9092 market-data-raw 2000 0.02"
```

Check the tables and monitor:

```bash
sbt "runMain com.backoffice.marketdata.monitoring.TableCounts"
sbt "runMain com.backoffice.marketdata.monitoring.PipelineMonitor"
```

Expected results for the standard run (after hand-injecting the book's two bad
records — a negative-price trade and a missing-symbol trade — via the Kafka
console producer as described in Step 4):

- Bronze: 2043 rows; Silver: 2000 rows; Quarantine: 2 rows
  (`invalid_trade_price`: 1, `missing_symbol`: 1)
- Reconciliation: bronze − silver − quarantine = 41 (duplicates removed)
- Bars: about 271 (varies slightly with the random seed; roughly 50+ bars per
  symbol out of 60 possible minutes)
- Note: bars for the final windows only appear once the watermark advances past
  the window end, so wait after the producer finishes, or send later-dated
  records to push the watermark forward.

## Tests

```bash
sbt test
# Book's expected output: ValidationSpec, BarBuilderSpec and
# MarketDataIntegrationSpec pass — 7 tests, 0 failures.
```

`MarketDataIntegrationSpec` is, by the book's own description, a smoke test of
data shape only; it does not actually send to Kafka (see Extraction notes).

## Layout

```text
market-data-pipeline/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
└── src/
    ├── main/scala/com/backoffice/marketdata/
    │   ├── generator/
    │   │   ├── TickGenerator.scala
    │   │   └── KafkaProducerApp.scala
    │   ├── streaming/
    │   │   ├── MarketDataStreamingJob.scala
    │   │   ├── Schemas.scala
    │   │   ├── Validation.scala
    │   │   ├── SilverStreamingJob.scala
    │   │   └── Deduplication.scala
    │   ├── bars/
    │   │   ├── BarBuilder.scala
    │   │   └── BarBuilderJob.scala
    │   └── monitoring/
    │       ├── PipelineMonitor.scala
    │       └── TableCounts.scala
    └── test/scala/com/backoffice/marketdata/
        ├── ValidationSpec.scala
        ├── BarBuilderSpec.scala
        └── MarketDataIntegrationSpec.scala
```

## Extraction notes (gaps, ambiguities, and inconsistencies — nothing invented)

1. **Validation.scala — final version, with one omission.** The book first
   presents `validationReason(tick: Column)` plus `isValid`, then deliberately
   flags that signature as a bug and instructs: change it to the no-argument
   `def validationReason: Column` and call it as `Validation.validationReason`
   after flattening. This extraction uses that corrected no-argument version
   (which is also what `ValidationSpec` and Appendix C use). The original
   `isValid` (`validationReason(col("tick")).isNull`) no longer matches the
   corrected signature, the book never shows a corrected `isValid`, and nothing
   in the book calls it — so it was **left out rather than guessed**. If you
   need it, its intended meaning is "no validation reason", but that corrected
   form is not in the book.
2. **SilverStreamingJob.scala — assembled final version.** The book builds this
   job in pieces: the Step 4 version (validation + silver/quarantine branches),
   then Step 5 replaces the plain silver write with
   `Deduplication.deduplicate(valid)`. This file is that assembled final
   version, with the Step 4 validation-call fix applied. Appendix C presents
   the same assembled logic under a different object name,
   `SilverStreamingJobFinal` (with a `buildSpark()` helper), as a reference
   listing — it was **not** extracted as a second file, to avoid two jobs with
   the same app name writing to the same tables. Reconcile against Appendix C
   if your working copy differs.
3. **Files not in the initial Directory Layout, but included.** The layout in
   Section 2.3 lists only 7 main files. `Deduplication.scala`,
   `SilverStreamingJob.scala`, and `BarBuilderJob.scala` are presented as
   complete listings later in the chapter, so they are included here under
   their matching packages. `TableCounts.scala` (Step 4) is likewise a complete
   listing, though absent from the layout, so it is included under
   `monitoring`.
4. **PrintTicks deliberately excluded.** Step 1's `PrintTicks` is explicitly a
   scratch check ("add a tiny main to a scratch file"), not a deliverable, so
   it is not in this directory.
5. **Source-count inconsistency in the book.** Appendix A.3 says "9 Scala
   sources" but then enumerates 10 files (2 generator + schemas + validation +
   deduplication + 2 streaming jobs + bar builder + bar job + monitor). This
   extraction has 11 main Scala files (the 10 enumerated, plus `TableCounts`)
   and 3 test files.
6. **plugins.sbt is empty.** The book gives no `plugins.sbt` content for
   Project 1 — it only says to confirm it is "empty of surprises". Part 1 says
   plugins are added only when a project demonstrates the need (sbt-assembly
   in Project 3, sbt-scalafmt in Project 10). No plugin was invented here.
7. **build.properties version conflict.** Part 2 pins `sbt.version=1.10.7` for
   this project (also confirmed in Appendix A.1), while Part 1's template pins
   `sbt.version=1.9.8`. The Part 2 project-specific pin (1.10.7) was used.
8. **Kafka client version difference, left verbatim.** Project 1's `build.sbt`
   uses Kafka clients 3.7.0, while Part 1's version table lists 3.6.1. The
   project build file was copied verbatim; the difference was not resolved.
9. **Unused imports retained verbatim.** Several listings import
   `io.delta.tables._` without using it (streaming jobs, bar job, monitor).
   They were copied as printed, not cleaned up.
10. **Integration test limitation (book's own caveat).**
    `MarketDataIntegrationSpec` imports `KafkaProducerApp` but never invokes it;
    it asserts only generator output shape (100 ticks, ≤ 5 symbols, sequence
    numbers ≥ 1, JSON field names). The book says the Kafka send itself is
    exercised manually and that full pipeline assertions are verified with
    `PipelineMonitor`, because streaming timing makes automated assertions
    flaky. Do not read this test as end-to-end coverage.
11. **Partial excerpts not extracted as files.** Step 5's standalone
    `val deduped = ...` snippet, the Step 4 "change the signature" fragment,
    SQL queries in Step 7, and the Appendix C/D/E variations (5-minute/daily
    bars, VWAP, session-aware bars, exercises) are not standalone files in the
    book and were not turned into files here. The dedup snippet's effect is
    incorporated into `SilverStreamingJob.scala` as described in note 2.
12. **No code was completed or "fixed" beyond the book's own corrections.**
    In particular, no Delta patch version, no missing file, and no corrected
    `isValid` was invented. Anything the book references but never shows as a
    complete file is listed here rather than fabricated.
