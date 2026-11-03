# Project 10 — Trade Surveillance and Anomaly Detection

Extracted Scala code for Project 10 (Surveillance) from Part 11 of the
Spark 4.3.0 edition of the book (`part-11-project10-surveillance.md`).
A complete, streaming trade surveillance system in Scala and Apache Spark
Structured Streaming that watches orders and trades, detects four classic
market-abuse patterns — wash trading, spoofing and layering, marking the
close, and unusual volume — scores and suppresses alerts, routes them into
a case-management queue, and backtests against synthetic data with planted
abuse cases.

**This code was extracted verbatim from the book and was NOT compiled in
this environment (no Java/Scala toolchain is available in this VM). See
"Extraction notes" below for gaps and inconsistencies found in the book's
listings, which were left exactly as written and not fixed.**

## Prerequisites (from the book)

JDK 17 or 21, Scala 2.13, sbt, Spark 4.3.0 in local mode, Delta Lake,
and Docker with Kafka and Postgres (the Docker Compose file from Part 1).
Confirm `java -version` reports 17 or 21 before anything else.

## Build

```bash
cd <this-project-directory>
sbt compile
```

The book's Step 0.6 expects compiling zero sources to succeed first as a
toolchain check; with the extracted sources present, `sbt compile` compiles
the full project instead.

## Start the local infrastructure

Use the Docker Compose file from Part 1, then create the topics:

```bash
docker compose up -d
sleep 20
docker exec kafka kafka-topics --create --bootstrap-server localhost:9092 \
  --topic orders --partitions 4 --replication-factor 1
docker exec kafka kafka-topics --create --bootstrap-server localhost:9092 \
  --topic trades --partitions 4 --replication-factor 1
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092
```

Expected output: `orders` and `trades`.

## Run the pieces

Sample data (one day, from the book's Step 1.5):

```bash
sbt "runMain com.trading.surveillance.backtest.GenerateSample"
```

Backtest (from Step 8.3; prints the per-detector precision/recall table):

```bash
sbt "runMain com.trading.surveillance.backtest.BacktestMain"
```

Full streaming system (from Appendix A.2 — two terminals; start the app
first, wait until its queries have started, then run the producer):

```bash
# Terminal 1:
sbt "runMain com.trading.surveillance.streaming.SurveillanceApp"
# Terminal 2:
sbt "runMain com.trading.surveillance.streaming.KafkaProducer 42 5"
```

The producer takes an optional seed and day count (`42 5` above); the book
expects terminal 2 to print roughly `Publishing ~26000 order events and
~11000 trades.` followed by `Done.` Wait at least two 30-second
micro-batch intervals (60 seconds) before querying the alerts table.

Worked triage session (from Step 7.3):

```bash
sbt "runMain com.trading.surveillance.cases.TriageDemo"
```

## Tests

```bash
sbt "testOnly com.trading.surveillance.WashTradeDetectorSpec"
sbt "testOnly com.trading.surveillance.SpoofingDetectorSpec"
sbt "testOnly com.trading.surveillance.MarkingCloseDetectorSpec"
sbt "testOnly com.trading.surveillance.UnusualVolumeDetectorSpec"
```

Expected (from the book): 4, 3, 2, and 2 tests respectively, all succeeding.

## Stopping, resetting, and replaying (from Appendix A.4)

```bash
# Stop the app with Ctrl-C in terminal 1, then:
rm -rf data/delta data/checkpoints
docker exec kafka kafka-topics --delete --bootstrap-server localhost:9092 \
  --topic orders
docker exec kafka kafka-topics --delete --bootstrap-server localhost:9092 \
  --topic trades
# Recreate topics as in "Start the local infrastructure" above, then republish:
sbt "runMain com.trading.surveillance.streaming.KafkaProducer 43 5"
```

## Analyst views (from Appendix A.3 — not source files)

These are temporary views to create in an analysis session, not files in
this project:

```sql
-- The triage queue: highest scores first, only actionable statuses.
CREATE OR REPLACE TEMP VIEW triage_queue AS
SELECT detector, symbol, accountIds, beneficialOwnerIds,
       score, evidence, windowStart, windowEnd
FROM delta.`data/delta/surveillance/alerts`
WHERE status IN ('QUEUED', 'ESCALATED')
ORDER BY score DESC;

-- Suppression audit: what was hidden, and why.
CREATE OR REPLACE TEMP VIEW suppression_audit AS
SELECT detector, symbol, suppressionReason, COUNT(*) AS n
FROM delta.`data/delta/surveillance/alerts`
WHERE status = 'SUPPRESSED'
GROUP BY detector, symbol, suppressionReason;

-- Repeat offenders: owners with alerts on multiple days.
CREATE OR REPLACE TEMP VIEW repeat_offenders AS
SELECT beneficialOwnerIds, COUNT(*) AS alert_count,
       COUNT(DISTINCT detector) AS detector_count
FROM delta.`data/delta/surveillance/alerts`
GROUP BY beneficialOwnerIds
HAVING COUNT(*) >= 3
ORDER BY alert_count DESC;
```

## Configuration

Thresholds live in `src/main/resources/application.conf` (wash-trade window,
spoofing sizes/cancel times, marking-the-close shares, unusual-volume
z-threshold, scoring cooldown/auto-close/escalate thresholds). The book
says to tune these after the Step 8 backtest, not to hard-code them in
detectors. Note: `SurveillanceApp` loads configuration with Typesafe
Config but, as extracted, its detector queries use hard-coded trigger
intervals and a static VWAP map rather than every value in this file.

## Standing book caveat — do NOT "fix" this

**Project 10's close-volume divisions will throw under Spark's ANSI
default on a zero-volume/flat-history day.** This is left unguarded by
design in the book (a loud failure rather than a silently wrong number).
The extracted code preserves that behaviour exactly; no guard was added.

Related Spark 4.3.0 note from the book: ANSI SQL mode is ON by default in
Spark 4.3.0, so division by zero, integer overflow, and invalid casts
throw instead of returning NULL. Do not turn ANSI mode off to make old
code pass.

## Delta Lake version — placeholder preserved verbatim

`build.sbt` pins Spark 4.3.0 and sets `val deltaVersion = "4.x"` with the
book's comment: check Delta Lake's compatibility table for the exact patch
for Spark 4.3.0 and substitute it for the placeholder. **No specific Delta
patch is claimed here as certified for Spark 4.3.0, and none was invented
during extraction.** Replace `"4.x"` with that exact patch before building.

## Extraction notes

Source: `trading-projects/parts-430/part-11-project10-surveillance.md`
(read-only; not modified). Shared template details cross-checked against
`trading-projects/parts-430/part-01-setup-foundations.md`.

1. **Project directory name:** `surveillance`, from the book's Step 0.1
   (`cp -r ~/trading-projects/template ~/trading-projects/surveillance`).
2. **No incremental files were merged or guessed.** The book presents each
   file once, as a complete listing, except where noted below; the final
   listing shown was used in every case. No code was invented, completed,
   or "fixed".
3. **DetectorRunner is wash-trade-only as written.** Step 2.5 says
   `detectors/DetectorRunner.scala` "will [be extended] with the other
   detectors in Steps 3–5", but no extended listing is ever shown. The
   extracted file contains only `washTradeQuery`. The full four-detector
   wiring lives separately in `streaming/SurveillanceApp.scala`, which
   does not call `DetectorRunner`.
4. **`gen.symbols` is referenced but not declared as a field.** Both
   `backtest/BacktestHarness.scala` (in `run`) and
   `streaming/KafkaProducer.scala` (in `main`) call `gen.symbols`, but
   `backtest/SyntheticDataGenerator.scala` declares its constructor
   parameter as plain `symbols: Seq[String] = Seq("ACME", "GLOB", "INNO",
   "VERT", "NOVA")` — not `val symbols`. As written, that member is not
   accessible from outside the class, so these two call sites are a
   likely compile failure. Left verbatim; the fix (declaring it `val`)
   was not applied.
5. **Orders and trades are generated twice, not paired.** In both
   `BacktestHarness.run` and `KafkaProducer.main`, normal orders and
   normal trades come from separate `gen.normalDay(s, d)` calls (once for
   `._1`, once for `._2`), so the random sequences advance independently
   and the trades do not correspond to the orders. Left verbatim.
6. **Scoring/suppression are imported but never invoked in the app.**
   `SurveillanceApp` imports `AlertScorer` and `SuppressionEngine`, but
   its code never calls `score` or `suppress`; a comment says the scoring
   and suppression step "runs as a batch job over the alerts table on a
   schedule (hourly in production, on demand in this project)" without
   showing that job. Its `casesPath` value is also defined but never
   used. The `AlertScorer`/`SuppressionEngine` objects themselves are
   complete and extracted as written.
7. **No separate `Metrics` file exists.** The Step 0.4 layout lists
   `Metrics` under `backtest/`, but the book never shows a `Metrics.scala`
   file; the metrics type `DetectorMetrics` lives inside
   `backtest/BacktestHarness.scala`. No separate file was created.
8. **Backtest lookalike tracking is a stub.** `BacktestHarness` computes
   `lookalike` orders (from `PlantedScenarios.marketMakerLookalike`) and
   adds them to the orders DataFrame, but `metrics` hard-codes
   `lookalikeAlerts = 0` and never distinguishes lookalike alerts from
   other false positives. Left verbatim.
9. **Partial excerpt excluded.** Step 5.3 shows a short daily-volume
   aggregation snippet (commented "In SurveillanceApp (completed in Step
   6)") with no package, object, or file name. It was NOT extracted as a
   separate file; the equivalent logic already appears in full inside
   `SurveillanceApp` as `dailyVolumesQuery`.
10. **Files whose names are inferred, not stated.** The book names no file
    for the Spoofing, Marking-Close, or Unusual-Volume test specs, for
    `TriageDemo`, or for the Appendix B/C listings. Paths were inferred
    from each listing's package and object/class name:
    `src/test/scala/com/trading/surveillance/{SpoofingDetectorSpec,
    MarkingCloseDetectorSpec, UnusualVolumeDetectorSpec}.scala`,
    `cases/TriageDemo.scala`, `detectors/ControllerLinkDetector.scala`,
    `cases/EvidenceReplay.scala`. The Appendix B detector is not wired
    into `SurveillanceApp` or `BacktestHarness` in the book, and no
    planted controller-link scenario is shown despite the appendix
    suggesting one be added.
11. **Not extracted (no code given in the book).**
    - `streaming/AlertQuery` (Appendix A.2): the book explicitly says
      this ten-line main is "a ten-line main you should write yourself as
      a consolidation exercise". No file was created for it.
    - The three analyst views in Appendix A.3: temporary SQL views only;
      reproduced above in this README, not as a source file.
    - A Docker Compose file: Step 0.3 says to use the Part 1 file; none
      is shown in Part 11, so none was created here.
12. **Synthetic generator is normal-flow only.** Step 1.4 says planted
    scenarios are "added in Step 8", but Step 8 presents them as the
    separate `backtest/PlantedScenarios.scala` object; the generator
    itself is never extended. The extracted generator therefore contains
    only `normalDay` (plus its helpers), exactly as shown in Step 1.4.
13. **sbt version discrepancy.** Part 11 Step 0.2 pins
    `sbt.version=1.9.9` in `project/build.properties`; the Part 1 shared
    template pins 1.9.8. The project-specific 1.9.9 was used here.
    `project/plugins.sbt` is `addSbtPlugin("com.eed3si9n" %
    "sbt-assembly" % "2.1.5")`, verbatim from Part 11.
14. **`application.conf` is HOCON, not Scala.** The book presents it in a
    `scala` code fence; it was extracted verbatim as
    `src/main/resources/application.conf`.
15. **Tuning log.** Appendix D's template was extracted verbatim as
    `docs/tuning-log.md` (it is a template to fill in, not code).
16. **Verification performed (no compilation).** Every `.scala` file was
    checked for balanced braces/parens/brackets, absence of Markdown
    fences or prose, and a package declaration matching its directory
    path — all passed. The book sources were not modified, nothing was
    pushed to GitHub, and nothing was written outside this project
    directory.
