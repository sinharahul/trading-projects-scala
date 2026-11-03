# Risk and Margin Engine — Project 7 (Part 8 of the book)

A complete risk and margin engine for a stock trading back office, extracted
from Part 8 (Project 7: Risk and Margin) of the book. It takes positions and
market prices as input, computes gross, net, and beta-adjusted exposure per
account and per desk, estimates Value at Risk and Expected Shortfall from
historical prices, computes margin requirements under a Regulation T style
model and a simple portfolio-margin haircut model, replays stress scenarios
against the book, monitors hard and soft limits, pushes breach alerts onto a
queue, refreshes risk intraday as streaming prices arrive, and produces a
daily risk report.

## Prerequisites

From the book (Part 1, and Part 8, Section 8.3): JDK 17 or 21, Scala 2.13,
sbt. The risk engine itself is pure Scala with no Spark dependency for
Steps 1–8, which is deliberate in the book: the core calculations must run
in unit tests in seconds.

## How to run it

From this project directory (`risk-margin`):

```bash
sbt compile
sbt test
sbt "runMain risk.RiskEngineApp"
```

Notes from the book:

- Run `sbt compile` first, before writing any logic — the book's Step 0b
  instruction. If the skeleton does not compile, nothing downstream can be
  trusted.
- Run `sbt test` at the end of Step 8 and confirm the whole suite is green
  before moving on.
- The end-to-end application (`src/main/scala/risk/RiskEngineApp.scala`,
  Appendix A of Part 8) loads prices, computes exposure, VaR, ES, margin,
  stress, and limits, and prints the Markdown report plus the JSON summary
  and the queued alerts. Expected condensed output for the fixture book
  (account A2, leveraged): exposure gross 187,000 and net 143,000;
  VaR 95% 998 and VaR 99% 1,040; ES 95% 1,051; margin call 14,850; stress
  results ranked with single-name ACME −50% worst at −60,000; three soft
  breaches; three alerts queued.
- Tests load price history from the relative path
  `src/main/resources/data/prices.csv`, so run sbt from this project root.
  The fixture book used throughout: account A1, desk DESK-1 — long 1,000
  ACME, long 500 GLOB, short 400 STAB; closing prices ACME 120, GLOB 90,
  STAB 55; long 165,000, short 22,000, gross 187,000, net 143,000,
  beta-adjusted 169,000.

Expected headline numbers (from the book's worked examples, all asserted
by the tests):

| Measure | Value |
|---|---|
| VaR 95% 1-day | 998 (20 observations) |
| VaR 99% 1-day | 1,040 (20 observations; see warning below) |
| Expected Shortfall 95% | 1,051 (1 tail observation) |
| Reg T initial margin | 115,500 |
| Reg T maintenance margin | 47,850 |
| Portfolio margin | 41,750 |
| Margin call, leveraged account A2 (cash 10,000, debit 120,000) | 14,850 |
| Stress: 2008-style / flash crash / correlation break / single-name ACME −50% | −54,600 / −13,070 / −28,600 / −60,000 |
| Limit breaches on the fixture book | 3 soft (gross, net, concentration); no hard breach |

Warning carried over from the book: with only 20 observations, the 99%
VaR interpolates between the same two worst days as the 95% VaR and
should not be treated as precise; ES 95% averages a single tail
observation and should be treated as a floor, not a fact.

## Project layout

```
build.sbt
project/
  build.properties
  plugins.sbt
src/
  main/
    scala/risk/
      RiskEngineApp.scala          # Appendix A end-to-end batch (package risk)
      domain/
        Domain.scala               # Position, Price, PriceTick, SymbolInfo, Account, AccountType
        Fixtures.scala             # Fixtures: fixture book, prices, account A1
      returns/
        PriceHistoryLoader.scala   # CSV price-history loader
      exposure/
        ExposureCalculator.scala   # Exposure + ExposureCalculator (byDesk, byAccount)
        Concentration.scala        # maxSymbolShare, maxSectorShare
      varx/
        HistoricalVaR.scala        # PortfolioValue, VaRResult, HistoricalVaR
        ExpectedShortfall.scala    # ESResult, ExpectedShortfall
      margin/
        MarginCalculator.scala     # MarginAccount, MarginResult, MarginCalculator
      stress/
        StressEngine.scala         # Scenario, StressResult, StressEngine (4 scenarios)
      limits/
        LimitChecker.scala         # Severity, Limit, Breach, Alert, AlertQueue,
                                   # InMemoryAlertQueue, LimitChecker
      streaming/
        StreamingRiskEngine.scala  # RefreshAudit, StreamingRiskEngine
      report/
        ReportGenerator.scala      # RiskReport, ReportGenerator (Markdown + JSON)
    resources/data/
      prices.csv                   # 21 trading days, 2026-09-01 to 2026-09-29
  test/scala/risk/
    ExposureSpec.scala  VaRSpec.scala  ESSpec.scala  MarginSpec.scala
    StressSpec.scala    LimitSpec.scala  StreamingSpec.scala  ReportSpec.scala
```

## Extraction notes

Read the book source fully; no code was invented, completed, or "fixed".
The code was **not compiled** — no Java/Scala toolchain is available in
this VM — so `sbt compile` / `sbt test` must be run by the reader to
confirm the book's claimed green suite.

1. **File names are inferred, code is verbatim.** Part 8 gives package
   layouts and code listings but no per-file names (unlike the
   `Create src/main/scala/.../FileName.scala:` pattern used elsewhere).
   Each listing block was therefore extracted intact as one file, named
   after its principal type, in the directory matching its package
   declaration. Files that bundle several types (e.g. `Domain.scala`
   holds Position, Price, PriceTick, SymbolInfo, Account, and
   AccountType; `LimitChecker.scala` holds the severity/limit/breach/
   alert/queue types plus the checker) are kept bundled exactly as the
   book presents them, not split up.
2. **No incremental / ambiguous final versions.** Each file appears
   exactly once in Part 8 as a complete listing. No file is built up
   across steps and no signature-change step occurs in this part, so
   there was no intermediate-vs-final choice to make.
3. **build.sbt is verbatim from Part 8, Section 8.3.2** (Scala 2.13.12,
   organization `com.backoffice`, name `risk-margin-engine`, ScalaTest
   3.2.17 in Test scope, Typesafe Config 1.4.3, plus the book's
   scalacOptions). It contains **no Delta Lake dependency and no Delta
   "4.x" placeholder** — correctly, because Part 8 states this engine is
   pure Scala with no Spark dependency for Steps 1–8. No Delta version
   was invented. The Spark/Delta scaling variant appears only as an
   extension exercise (Section 8.13) with no code listing, so it is not
   included here.
4. **project/build.properties** is `sbt.version=1.9.8`, taken from Part 1
   (Step 7.2), which states every project in the book pins 1.9.8.
5. **project/plugins.sbt is comment-only.** Part 1 (Section 1.23.5)
   states the book uses exactly two plugins, introduced only when
   needed: sbt-assembly for Project 3 and sbt-scalafmt for Project 10.
   Neither applies to this project, and no plugin lines are given for
   it, so none were invented; the file records that fact.
6. **prices.csv: the book's label line was dropped.** The CSV listing in
   Section 8.3.4 begins with a comment line
   `# src/main/resources/data/prices.csv`. That line is a label, not
   data: `PriceHistoryLoader` treats the first line as the header, so
   including it would break loading. The extracted file therefore starts
   with the real header `date,ACME,GLOB,STAB` and contains the 21 data
   rows (2026-09-01 to 2026-09-29) exactly as printed.
7. **No code for the extension exercises, pitfalls, glossary, or
   appendices B/C.** Sections 8.12–8.19 (pitfalls, extension exercises
   such as Monte Carlo VaR, Spark scaling, and Kupiec backtesting,
   interview questions, the end-to-end checklist, the glossary, and the
   day-by-day margin-call narrative) contain no code listings and were
   not turned into code. The one code listing in the appendices —
   Appendix A's `RiskEngineApp` — is included.
8. **Streaming engine comment/code mismatch (kept verbatim).** The
   comment in `StreamingRiskEngine.onTick` says VaR is "passed in by the
   caller through `lastVarDollars`", but the code constructs a dummy
   `VaRResult(0.99, 1, 0.0, BigDecimal(0), 0.0, LocalDate.now(), 0)` and
   filters the `var-99-1day` limit out of the tick path, so the tick
   path checks exposure and concentration limits only. This matches the
   book's stated cadence (VaR refreshes on schedule, not per tick) but
   the comment's `lastVarDollars` parameter does not exist in the code.
   Not changed; flagged for the reader.
9. **Unverified edge cases (kept verbatim, not fixed).**
   - `ReportGenerator.toJsonSummary` calls
     `stressResults.map(_.pnl).min`, which would throw on an empty
     stress list. The book never shows an empty stress list.
   - `HistoricalVaR.portfolioValueSeries` takes its date list from
     `history.values.head`, i.e. it assumes every symbol shares the same
     dates. That holds for output of `PriceHistoryLoader`, which builds
     every symbol's series from the same CSV rows.
   - Several calculations convert `Double` to `BigDecimal`
     (`BigDecimal(beta)` in exposure, `BigDecimal(haircut + shortUplift)`
     in portfolio margin, `BigDecimal(1.0 + shock)` in the stress
     engine). The book's exact-equality tests (e.g. portfolio margin
     41,750, beta-adjusted exposure 169,000, stress P&L −54,600) depend
     on how those conversions behave. Since the code was not compiled
     here, confirm those tests pass on first `sbt test` run rather than
     assuming it.
10. **Minor verbatim quirks kept as printed.** `VaRSpec` imports
    `risk.domain.Price` without using it. The domain `Account` class
    (no debit balance) and the margin module's separate `MarginAccount`
    (with debit balance) are both kept; the book explicitly explains
    why the margin view is separate.
11. **No main entry point for Steps 1–8 alone.** The only runnable
    application in Part 8 is Appendix A's `RiskEngineApp`; the steps
    themselves are exercised through the eight Spec files.
