# Settlement and Reconciliation Engine — Project 5 (settlement-recon)

Extracted Scala code for Part 6, Project 5 (Settlement and Reconciliation)
of the trading back-office book. Source part (read-only, not modified):
`trading-projects/parts-430/part-06-project5-settlement-recon.md`.

**This code was not compiled** — no Java/Scala toolchain is available in
this VM. Nothing was pushed to GitHub.

## How to run

From this project directory:

```bash
cd settlement-recon
sbt run
```

You should see, in order: the three Step 1 instruction lines, the parsed
statement entries, the match results (exact 3, fuzzy 1, unmatched
instructions 1, unmatched statements 3), the 5 breaks, the resolutions
(2 auto-resolved, 3 in the manual queue), the aging report as of
2026-11-03 and as of 2026-12-10, the cash reconciliation (total
difference -34981.98), and the daily dashboard for 2026-11-03
(5 total breaks, 3 open, 2 auto-resolved, 40% auto-resolution rate).

Run the tests separately:

```bash
sbt test
```

Run from the project root: `Main` and `EndToEndSpec` read
`data/custodian-bny-2026-11-02.csv` as a relative file path, and the
holiday/SSI files are loaded from `src/main/resources` via
`Source.fromResource`. The book notes that running tests from an IDE
with a different working directory breaks the data-file lookup; moving
the sample statement into `src/test/resources` is its recommended fix.

The second-day file `data/custodian-bny-2026-11-03.csv` (STMT-101,
STMT-102) is the book's Appendix A exercise data. It is included for
reference; `Main` as printed does not parse it — the exercise describes
concatenating both statement files and re-running the pipeline by hand.

## Layout

- `build.sbt` — copied verbatim from the project's build-file section.
  Only ScalaTest is a dependency; this project uses no Spark or Delta
  Lake, so there is no Delta version (and no "4.x" placeholder) in it.
- `project/build.properties` — `sbt.version=1.9.8`, from the Part 1
  project template (every project in the book pins 1.9.8).
- `project/plugins.sbt` — comment only, no plugins. Part 1 (§1.23.5)
  introduces sbt-assembly only in Project 3 and sbt-scalafmt only in
  Project 10; neither is used by this project, so no plugin version
  was invented.
- `src/main/scala/recon/` — `Domain`, `SettlementCalendar`,
  `SsiRepository`, `InstructionGenerator`, `StatementParser`,
  `Matcher`, `BreakClassifier`, `ResolutionEngine`, `Reporting`,
  `Dashboard`, `Main`. All in package `recon`, matching the directory.
- `src/test/scala/recon/` — one spec per module plus `EndToEndSpec`.
- `src/main/resources/` — `us-holidays-2026.txt`, `ssi.csv`.
- `data/` — sample custodian statements.

## Extraction notes

- **Final versions only.** `Main.scala` is assembled from the book's
  initial runner plus each step's "extend the runner" snippet, in step
  order, so it is the final Step 8 runner — not the intermediate
  Step 1–7 runners.
- **Parser fix applied, as instructed by the book.** The book first
  prints a `parseLines` with a branch that calls `parseWithHeader`
  and then returns an empty result, explicitly calls that a genuine
  wart, and prints a clean replacement. This extraction uses the clean
  replacement (plus its `collectResults` helper) and drops the faulty
  branch. Nothing else in `StatementParser.scala` was changed.
- **File splits follow the book's Appendix A file map.** The calendar
  and SSI repository are split into `SettlementCalendar.scala` and
  `SsiRepository.scala` (the book says either arrangement compiles).
  `Matcher.scala` bundles `StatementConventions`, `MatchTolerances`,
  `MatchKind`/`MatchResult`, and `Matcher` (all Step 3 material);
  `Reporting.scala` bundles the aging report and the cash
  reconciliation. Duplicate `package`/`import` lines from the separate
  printed listings were consolidated at the top of those two combined
  files; no code was altered.
- **No code was invented, completed, or "fixed"** beyond the two
  items above (the book-directed parser rewrite and import
  consolidation). In particular:
  - No partial listings were promoted to complete files. Every
    `.scala` file here is a complete listing from the book.
  - No file the book references but never shows was created. The
    exercises (partial settlements, multi-custodian matching,
    persistence, streaming ingestion, reference-based matching,
    date-format fallback, quantity-scale normalisation, SSI
    versioning by effective date) are described in prose only and are
    **not** implemented here.
- **Gaps / ambiguities (flagged, not resolved by guessing):**
  1. Currency normalisation: the Step 2 prose says normalisation
     includes uppercasing the currency in `parseFields`, but the
     printed `parseFields` code only trims fields — it does not
     call `toUpperCase`. Extracted as printed; a lower-case currency
     in a statement file would therefore not match an instruction.
  2. Negative aging: the Appendix A troubleshooting text advises
     clamping negative ages to zero, but the printed `AgingReport`
     code does not clamp — a break detected after the report date
     falls into no bucket (though it still counts in `totalOpen`).
     Extracted as printed.
  3. Step 3 prose says to add "an AAPL buy in the equity account
     that will compete with STMT-007's wrong-account line", but the
     printed snippet adds only T004 (TSLA) and T005 (NVDA), and all
     printed expected outputs (matcher counts, 5 breaks, dashboard)
     are consistent with only T004/T005. The snippet was followed,
     not the prose sentence; no extra AAPL trade was invented.
  4. DateMismatch auto-resolution: the Step 5 table says
     auto-resolve when settled 1–2 business days late, while the
     printed code auto-resolves every `DateMismatch`. In practice
     this coincides here because the fuzzy matcher only admits
     date differences within the 2-business-day tolerance, but the
     code itself does not re-check the distance.
  5. `SettlementLeg` (`ReceiveSecurities`/`DeliverSecurities`) is
     defined in `Domain.scala` but never used by the generator or
     matcher as printed. Left as printed.
  6. The book's Step 2 prose says "applied in `parseFields`"
     normalisation is limited to trim/uppercase/ISO dates; the
     date-format fallback and quantity-scale normalisation are
     exercises only (see above) and are not in the code.
- **Verification done here:** every `.scala` file has balanced
  braces/parens/brackets, no Markdown fences or prose, and a
  `package recon` line matching `src/.../recon`. Companion
  class/object pairs (e.g. `SettlementCalendar`,
  `SsiRepository`, `FeeSchedule`, `MatchTolerances`,
  `AgingReport`, `Dashboard`) are intentional Scala companions,
  not duplicate definitions. The book sources were only read, and
  nothing was written outside this project directory.
