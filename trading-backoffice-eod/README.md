# Project 9 — End-of-Day Close and Data Warehouse (`trading-backoffice-eod`)

Extracted from Part 10 of the book (Spark 4.3.0 / Scala 2.13 / JDK 17 or 21).
The book's project directory name is `trading-backoffice-eod` (Part 10, §10.3).

**The code was NOT compiled.** There is no Java/Scala toolchain in this VM, so
nothing here has been compiled or run. Several extracted listings are also
known not to compile or run as printed — see "Extraction notes" below.
Nothing was invented or "fixed" to paper over those gaps.

## What it builds

A dependency-ordered EOD batch for business date 2026-10-30 (sample day):
final positions snapshot → daily P&L and fees → double-entry ledger →
reporting aggregates, with a data-quality gate after each job, a small
Scala orchestrator, run history, a silver trade loader (corrections /
cancellations), a Postgres ledger mirror, and a morning status pack.

Money is integer cents (`Money`), quantities are whole shares
(`Quantity`); never use `Double` for money in this project.

## Prerequisites (from Part 1)

JDK 17 or 21, Scala 2.13.12, sbt 1.9.8 (pinned in
`project/build.properties`), local Spark 4.3.0, Delta Lake, and the
Docker Kafka/Postgres stack from Part 1. Delta Lake: see the build-file
note below — do not guess a patch version.

## How to run it (as printed in the book)

```bash
sbt compile
sbt "runMain com.backoffice.eod.EodMain 2026-10-30"
sbt "testOnly com.backoffice.eod.OrchestratorSpec"
sbt "runMain com.backoffice.eod.jobs.DimDateGenerator"
sbt "runMain com.backoffice.eod.jobs.SilverTradeLoader 2026-10-30"
```

Expected full-day output (Part 10, §10.12):

```
[finalize_positions] SUCCEEDED, 4 rows, attempt 1
[positions_gate] SUCCEEDED, 4 rows, attempt 1
[compute_pnl] SUCCEEDED, 4 rows, attempt 1
[pnl_gate] SUCCEEDED, 4 rows, attempt 1
[post_ledger] SUCCEEDED, 51 rows, attempt 1
[ledger_gate] SUCCEEDED, 51 rows, attempt 1
[build_aggregates] SUCCEEDED, 8 rows, attempt 1
[aggregates_gate] SUCCEEDED, 2 rows, attempt 1
EOD 2026-10-30 complete: success = true
```

Authoritative sample-day expectations (§10.12 — the book states this
section wins where an earlier illustrative table disagrees):

- `fact_positions`: ACC-1001/AAPL 170 @ 17356, 18000, 3060000, 109480;
  ACC-1001/MSFT 120 @ 20838, 43000, 5160000, 2659440;
  ACC-1002/AAPL -50 @ 17600, 18000, -900000, -20000;
  ACC-1002/TSLA 50 @ 25000, 26000, 1300000, 50000.
- `fact_daily_pnl`: ACC-1001/AAPL 18680 / 99480 / 300 / 5960 / 111900;
  ACC-1001/MSFT 205750 / 2609440 / 200 / 33057 / 2781933;
  ACC-1002/AAPL 0 / -20000 / 100 / 1496 / -21596;
  ACC-1002/TSLA 15000 / 50000 / 200 / 4701 / 60099
  (columns: realized / unrealized change / commission / fees / net).
- Aggregates: MSFT 460 shares / 19445000 cents / 2 trades;
  AAPL 250 / 4386000 / 4; TSLA 110 / 2765000 / 2.
  Desk P&L: Equities-1 net 2893833; Equities-2 net 38503.
  Ledger: 51 rows; total debits = total credits overall and per account;
  realized gain total 239430; unrealized change total 2738920;
  commissions 800 (8 final trades × 100); fees 45214.
  Headline totals: 8 trades, total value 29596000 cents, net P&L 2933336.

Sample inputs are in `data/sample-day/` (trades incl. the T003 correction
200 → 210 and the cancelled T009, opening positions, closing prices, the
MSFT 2-for-1 split). The split is applied at end of day to the full day's
MSFT position, as the book documents — a simplification, not a
production timestamped application.

## Layout

- `build.sbt`, `project/build.properties`, `project/plugins.sbt`
- `src/main/scala/com/backoffice/eod/` — `EodMain.scala`
- `src/main/scala/com/backoffice/eod/model/` — `Money.scala`,
  `SurrogateKeys.scala`, `PositionMath.scala`, `FeeSchedule.scala`,
  `EodJob.scala` (failure types + `EodJob`/`Gate` traits, concatenated
  from the two consecutive model listings in the book, per the §10.16
  file list)
- `src/main/scala/com/backoffice/eod/jobs/` — `DimDateGenerator.scala`,
  `Orchestrator.scala` (orchestrator + `RunHistory`, per the §10.16
  file list), `FinalizePositionsJob.scala`, `ComputePnlJob.scala`,
  `PostLedgerJob.scala`, `BuildAggregatesJob.scala`, `Gates.scala`
  (all four gates), `SilverTradeLoader.scala`, `LedgerMirrorJob.scala`,
  `MorningStatusPack.scala`
- `src/main/scala/com/backoffice/eod/ledger/` — `LedgerPoster.scala`
  (`LedgerLine`, `ChartOfAccounts`, `LedgerPoster`)
- `src/main/scala/com/backoffice/eod/gates/` — created empty: the book's
  §10.3 layout creates this directory, but every gate class is declared
  in package `com.backoffice.eod.jobs` and listed in §10.16 as
  `jobs/Gates.scala`, so no file belongs in `gates/`.
- `src/main/resources/sql/` — `01_dimensions.sql` (the only SQL filename
  the book gives); `02_fact_tables.sql`, `03_aggregates.sql`,
  `04_ledger_mirror_postgres.sql` (see Extraction notes)
- `src/test/scala/com/backoffice/eod/` — `OrchestratorSpec.scala`,
  `RerunSpec.scala`
- `data/sample-day/` — the four sample CSVs

## Extraction notes — gaps, ambiguities, and inconsistencies (NOT fixed)

1. **Not compiled.** No Java/Scala/sbt toolchain exists in this VM.
   Treat every file as a verbatim book listing, not as verified code.
2. **Delta version placeholder (build.sbt, verbatim).** The book pins
   Spark 4.3.0 but deliberately gives Delta only as `"io.delta" %%
   "delta-spark" % "4.x"` with the comment to substitute the exact
   patch from Delta Lake's compatibility table for Spark 4.3.0. No
   patch was invented here. The build will not resolve as written
   until a real patch replaces `4.x`. Note also that `build.sbt`
   pins ScalaTest 3.2.18 and Postgres 42.7.3 (Part 10's listing),
   whereas Part 1's template table pins ScalaTest 3.2.17 and
   Postgres 42.7.1 — both are reproduced as the book states them.
3. **`project/plugins.sbt` is comment-only.** Neither Part 1 nor
   Part 10 gives a `plugins.sbt` for this project. Part 1 §1.23.5
   names only sbt-assembly (Project 3) and sbt-scalafmt (Project 10)
   for the whole book. No plugin dependency was invented.
4. **`project/build.properties`: `sbt.version=1.9.8`.** Taken from
   Part 1 §7.2 ("Every project in this book pins 1.9.8").
5. **`DimDateGenerator` — no `main`, wrong `prev_business_date`,
   schema mismatch.** The book's run command is
   `sbt "runMain com.backoffice.eod.jobs.DimDateGenerator"`, but the
   listing is an `object` with only a `run(spark, holidays)` method —
   no `main`. Its `prev_business_date` computation uses
   `businessDays.indexWhere(!_.isAfter(d))`, i.e. the *first*
   business day ≤ d, so `idx` is 0 and `prev` is `null` for every
   date after the window start — contradicting the book's own
   checks (2026-11-02 → 2026-10-30). Its DataFrame also has 12
   columns (no `date_key`) while `01_dimensions.sql` declares 13,
   and its date columns are strings, not DATEs. The printed
   "dim_date written: 4018 rows" line is not produced by the code.
   All reproduced verbatim; do not use this generator until it is
   corrected upstream.
6. **`OrchestratorSpec` will not compile as printed.**
   It uses `new RunHistoryStub`, but the book never shows that
   class — it is explicitly "left as a five-minute exercise".
   Worse, `Orchestrator` takes a concrete `RunHistory`
   (a Spark-backed class requiring a `SparkSession`), not a
   trait, so a no-arg in-memory stub cannot be passed without
   changing `Orchestrator`'s signature. No stub was invented.
7. **`RerunSpec` is a skeleton, not a test.** The book says the
   bodies are "intentionally sketched" and the harness that invokes
   `EodMain` twice is omitted; both tests just call `succeed`.
   Reproduced verbatim.
8. **`PnlJobSpec` is referenced but never shown.** The book says to
   run `sbt "testOnly com.backoffice.eod.PnlJobSpec"` and describes
   its assertions (incl. the TSLA row: realized 15000, unrealized
   change 50000, commission 200, fees 4701, net 60099), but no
   listing for it exists. It was omitted, not invented. The same
   applies to the Step 7 "deliberate sabotage" gate tests, which
   are described but never listed.
9. **`PostLedgerJob` — opening positions are an empty map, by the
   book's own admission.** The listing has
   `val opening = Map.empty[(Long, Long), PositionState]` and looks
   up `(acc, 0L)`, so every sale is folded from a flat position.
   The book flags this: "the full implementation loads the opening
   state from the previous day's `fact_positions` exactly as the
   P&L job does, and the code above is abbreviated". As printed,
   the ledger will mis-post any trade against an opening position
   (e.g. ACC-1001's opening AAPL/MSFT longs) — closing sales will
   be treated as short-opening sales. Not fixed here.
10. **`LedgerPoster` zero-line trap (flagged by the book).**
    `postBuy`/`postSell` construct lines that may be zero and only
    filter them afterwards, but the `LedgerLine` constructor
    `require`s exactly one non-zero side, so a zero commission/fee
    line would throw before filtering. In the sample, commissions
    and fees are always positive, so the path is not hit. Also,
    the promised rounding account 5020 ("Rounding adjustments",
    §10.13 Pitfall 4) is never implemented in `LedgerPoster`, and
    `FeeSchedule.percentageFee`'s docstring claims it returns a
    `(fee, remainder)` tuple while the code returns a single `Long`.
11. **Gate dependency rule is stated but not implemented.**
    §10.10 says the next job must depend on the *gate*, never
    directly on the previous job — but `ComputePnlJob` declares
    `Seq("finalize_positions")`, `PostLedgerJob` declares
    `Seq("compute_pnl")`, and `BuildAggregatesJob` declares
    `Seq("post_ledger")`. The DAG only orders correctly because
    `EodMain` lists the jobs in execution order and the
    topological sort visits them in that order. Reproduced verbatim.
12. **`gold.fact_trades` is never written by any job in the chapter.**
    `BuildAggregatesJob` and `AggregatesGate` read it (and the
    morning reports join it), but no listing populates it from
    silver, and no loader computes its `amount_cents` /
    `commission_cents` / `fee_cents` columns. Likewise, no seed
    data is shown for `dim_account`/`dim_security` (so the
    "Equities-1 / Equities-2" desk mapping in the expected output
    has no source listing), for `silver_prices`,
    `silver_corporate_actions`, or for the previous day's
    `fact_positions` — only the CSVs in `data/sample-day/` are given.
13. **`FinalizePositionsJob` / `ComputePnlJob` only emit traded
    pairs.** Both jobs group by the day's trades, so an account-
    security position held all day with no trades that day produces
    no snapshot/P&L row, despite the stated grain ("one row per
    account per security per day"). The positions gate's
    completeness check only covers traded pairs, so it would not
    catch this either.
14. **`SilverTradeLoader` — no `main`, schema mismatch.**
    The book's run command is `runMain ... SilverTradeLoader`,
    but the listing is a class with a `run(date)` method and no
    `main`. It also reads bronze columns (`receipt_seq`,
    `account_key`, `security_key`, `correction_of`, `cancelled`)
    that do not exist in the supplied CSVs (which use
    `account_number`/`ticker` and have no receipt sequence); no
    CSV-to-bronze ingestion job is shown. It is also not wired
    into the `EodMain` DAG.
15. **`LedgerMirrorJob` and `MorningStatusPack` are not in the DAG.**
    `EodMain` wires only the four jobs and four gates. The mirror
    takes JDBC credentials in its constructor and has no wiring or
    `main`; the status pack's docstring says it "writes a single
    text report to the reports directory", but `build()` only
    returns a `String` and writes no file.
16. **SQL filenames 02–04 were not given by the book.** Only
    `src/main/resources/sql/01_dimensions.sql` is named in the
    text. The fact-table DDL, aggregate DDL, and Postgres mirror
    DDL are verbatim listings, but their filenames here
    (`02_fact_tables.sql`, `03_aggregates.sql`,
    `04_ledger_mirror_postgres.sql`) are extraction organisation,
    not book filenames. The morning-report queries (§10.9), the
    rerun diff query (§10.11), the check queries, and the ten-query
    Morning SQL Cookbook (§10.12e) are queries in prose, not
    presented as files, so they were not extracted as files.
17. **Earlier illustrative tables drift from the code; §10.12 wins.**
    The book itself flags two drifts: the Step 3 positions table
    shows MSFT avg cost 21250 / unrealized 2610000, while the job
    (split applied after the day's trades) gives 20838 / 2659440,
    as §10.12 states; and the Step 4 P&L table's fee/net columns
    do not match the stated fee schedule (e.g. TSLA fees 1745 vs
    the correct 4701), again corrected in §10.12. The §10.12
    numbers are quoted in this README as authoritative. The
    "MSFT desk" figures in Step 6 do match §10.12.
18. **Minor inconsistencies reproduced verbatim:** `DimDateGenerator`
    and `FinalizePositionsJob` import Spark `functions` as `F`
    without using it; `ComputePnlJob` computes a trade `amount`
    it never uses; `SurrogateKeys`' comment claims "the loader
    checks for collisions", but no such check is shown; the
    sample-day text says a "missing price" is exercised, but the
    supplied prices CSV covers all three tickers, so no missing-
    price case is present in the data.
19. **Book sources untouched.** No file under
    `trading-projects/parts-430/` was modified; all output is
    under `github-upload/trading-projects-scala/trading-backoffice-eod/`.
    Nothing was pushed to GitHub.
