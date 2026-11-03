# Regulatory Reporting Pipeline — Project 8

Extracted from Part 9 (Project 8: Regulatory Reporting) of the trading-projects
book (Spark 4.3.0 edition, `parts-430/part-09-project8-regulatory-reporting.md`).
Book sources were not modified, and nothing was pushed to GitHub.

## What this is

A regime-neutral regulatory reporting pipeline in pure Scala (except for the
optional Spark wiring in Step 9):

1. **Order lifecycle linking** — group order events by order id, sort by event
   timestamp (then event id), flag trades with no execution event and orphan events.
2. **CAT builder** — one record per reportable event, with a deterministic linkage
   key `firmId|orderId|reportingDate` and a computed late flag.
3. **MiFIR builder** — one record per transaction, with buyer/seller derived from
   side + capacity + parties, ISIN preferred (symbol fallback) for instrument
   identification, and short-selling indicator `SESH` / `NOSE`.
4. **Validation** — mandatory-field, format, and cross-field rules, collecting
   all violations per record instead of throwing on the first.
5. **Submission files** — pipe-delimited files with header, body, and a trailer
   carrying control totals (record count, total quantity, total notional).
6. **Acknowledgement handling** — accepted / rejected / late-accepted, with a
   versioned correction flow (new version, never an in-place edit) and a
   missing-acknowledgement check.
7. **Audit trail** — append-only store answering: every version of a report,
   submissions for a date/regime, validation failures for a date, reports in a
   state, and a completeness check against expected trade ids.

## Layout

```
regulatory-reporting/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── src/main/scala/com/trading/reporting/
│   ├── CoreModel.scala            # Trade, OrderEvent, InstrumentReference, PartyReference, EnrichedTrade
│   ├── ReportSchemas.scala        # CatReport, MifirReport
│   ├── OrderLinker.scala          # LinkResult, OrderLinker
│   ├── LinkerDemo.scala           # Step 2 demo driver
│   ├── CatBuilder.scala
│   ├── MifirBuilder.scala
│   ├── Validators.scala
│   ├── SubmissionWriter.scala     # ControlTotals, SubmissionFile, SubmissionWriter
│   ├── AckProcessor.scala         # ReportState, Acknowledgement, ReportVersion, AckProcessor
│   ├── AuditStore.scala
│   ├── Enricher.scala             # Deep-dive enrichment (separate from the linker)
│   ├── ReportingSparkJob.scala    # Step 9 Spark/Delta wiring — see Extraction notes
│   └── ReportingPipelineDriver.scala  # End-to-end driver over the test fixtures
└── src/test/scala/com/trading/reporting/
    ├── Fixtures.scala
    └── ReportingPipelineSpec.scala
```

## How to run

Prerequisites from Part 1: JDK 17 or 21, Scala 2.13, sbt.

```bash
cd regulatory-reporting
sbt compile
sbt test
sbt "runMain com.trading.reporting.LinkerDemo"
sbt "runMain com.trading.reporting.ReportingPipelineDriver"
```

Expected output (from the book, Step 9 driver):

```
Linked trades: 3
Unlinked or orphan results: 0
CAT records built: 9
MiFIR records built: 3
CAT valid: 9, violations: 0
MiFIR valid: 2, violations: 1
  VIOLATION FIRM-A-TRD-3 field=investmentDecisionMaker rule=MANDATORY
MiFIR file trailer: TRL|2|850|140440
Completeness: reported=List(TRD-1, TRD-2) missing=List(TRD-3)
```

Trade 3 is missing from the submitted set by design: it has no decision maker,
fails MiFIR validation, and sits in the error queue — it is not silently dropped.

Expected test values worth knowing (the book explains why they differ from the
prose worked examples, which use different fixtures):

- CAT control totals for the fixture order: `TRL|3|2800|140200`
  (event quantities 1000 + 1000 + 800; notional from the execution only).
- CAT file for all three fixture orders (appendix): `TRL|9|8400|420600`.
- MiFIR file for the two valid fixture trades: `TRL|2|850|140440`.
- Five-event sample order in Step 6 prose: `TRL|5|3600|140200`.
  CAT totals sum *event* quantities, not trade quantities.

## Extraction notes

Read these before building; nothing below was "fixed" — the code is verbatim
from the book, and the items below are the book's own gaps/ambiguities.

1. **File names are assigned by the extractor, not the book.** Part 9 gives no
   `Create src/main/scala/.../FileName.scala` markers and no file names for any
   listing. Each listing was extracted verbatim into the file named above,
   chosen from its principal type/object. No listing was split, merged, or
   completed, and no file is shown incrementally in two versions — every
   listing appears exactly once, so each extracted file is the book's final
   version of that listing.
2. **Not compiled.** No Java/Scala toolchain is available in this VM, so the
   code was verified only for balanced braces/parens/brackets, absence of
   markdown fences/prose, and package-vs-directory match. It has not been
   compiled or tested.
3. **`build.sbt` is verbatim from Part 9** (Scala 2.13.12, organisation
   `com.trading`, ScalaTest 3.2.17 test-only). It contains no Spark or Delta
   dependency and no Delta "4.x" placeholder — Part 9's build file is
   deliberately small and pure-Scala.
4. **`ReportingSparkJob.scala` cannot compile under that verbatim `build.sbt`.**
   It imports `org.apache.spark.sql.{Dataset, SparkSession}` and reads
   `format("delta")` tables, but no Spark/Delta dependency is declared.
   Part 1's version table (Spark 4.3.0, Delta Lake 4.x — exact patch to be
   taken from Delta Lake's compatibility table for Spark 4.3.0, never guessed)
   would be needed to build it. That dependency was **not** added, because the
   task required `build.sbt` verbatim. Treat the Spark job as an optional
   wiring sketch until those dependencies are added deliberately.
5. **`project/build.properties` (`sbt.version=1.9.8`) comes from Part 1**
   (Steps 7.2 / Section 1.14 template). Part 9 states no sbt version of its own.
6. **`project/plugins.sbt` contains only a comment.** Part 1 (Section 1.23.5)
   names exactly two plugins for the whole book — sbt-assembly (Project 3)
   and sbt-scalafmt (Project 10) — and gives no plugin versions for Project 8.
   No plugin or version was invented.
7. **`CatBuilder.build` takes an unused `reportIdPrefix` parameter.** It is
   never used in the body. Preserved verbatim.
8. **CAT submission lines are not unambiguously pipe-parseable.** The linkage
   key itself contains pipes (`FIRM-A|ORD-1|2026-10-31`), and the writer joins
   fields with `|`, so the linkage key appears as three pipe-separated fields
   in the body (as in the book's appendix). Preserved verbatim; do not rely on
   a naive pipe split to recover the linkage key.
9. **Fixture reuse (book acknowledges it).** `Fixtures.eventsFor` reuses the
   same timestamps, side (`Buy`), and quantities (1000/1000/800) for all three
   orders, including the SellShort option trade (TRD-2, quantity 50) and the
   broken trade (TRD-3). The book's appendix notes this repetition is a
   testing convenience, not realistic data; records are distinct by order id.
10. **`AuditStore.completeness` uses substring matching**
    (`submitted.exists(_.contains(id))`), so an expected id such as `TRD-1`
    would also match a submitted `TRD-10`. Preserved verbatim.
11. **No partial or missing listings were found.** Every code listing in Part 9
    is a complete file (or, for the tests, a complete fixture/spec file), and
    every step's code is present: Steps 1–9, the enrichment deep dive, and the
    Step 9 Spark job + end-to-end driver. The prose-only sections (exercises,
    pitfalls, case study, monitoring, runbook, glossary) contain no additional
    code to extract.
12. **Inconsistency to be aware of, not a code gap:** Step 2's worked example
    and Step 3's sample use a five-event order (receipt, routing, modification,
    two executions), while the test fixture uses a three-event order. Their
    control totals therefore differ legitimately (see "How to run" above).
    The book flags this explicitly in the testing section.
