# Data Engineering Projects — Stock Trading Back Office in Scala

Code extracted from *Data Engineering Projects — Stock Trading Back Office in Scala*
(Spark 4.3.0 edition), Projects 1–10. Each project is a standalone sbt project
in its own directory. See each project's own README for how to run it and for
that project's extraction notes.

## Projects

| # | Project | Directory |
|---|---|---|
| 1 | Market Data Ingestion Pipeline | `market-data-pipeline/` |
| 2 | Trade Capture and Validation | `trade-capture/` |
| 3 | Positions and P&L | `positions-pnl/` |
| 4 | Corporate Actions | `trading-backoffice/` (sbt project name: `corporate-actions-processor`) |
| 5 | Settlement and Reconciliation | `settlement-recon/` |
| 6 | Reference Data Golden Copy | `refdata/` |
| 7 | Risk and Margin | `risk-margin/` |
| 8 | Regulatory Reporting (CAT / MiFIR) | `regulatory-reporting/` |
| 9 | EOD Close and Warehouse | `trading-backoffice-eod/` |
| 10 | Surveillance | `surveillance/` |

Note: Projects 4 and 9 use similar directory names (`trading-backoffice` vs
`trading-backoffice-eod`) because those are the names given in the book.

## Toolchain

- Apache Spark **4.3.0**
- Scala **2.13** (per-project minor versions vary; see each `build.sbt`)
- JDK **17 or 21** (Java 8 and 11 are not supported by Spark 4.3.0)
- sbt (per-project version in each `project/build.properties`)

## Before you build — read this

1. **Delta Lake "4.x" placeholder.** Several projects' `build.sbt` files use
   a `"4.x"` placeholder for the Delta Lake version, exactly as printed in
   the book. The reader must substitute the real patch from Delta Lake's
   compatibility table for Spark 4.3.0 before building — do not guess a
   patch number, because Delta is tightly coupled to the Spark version.
   Projects that are pure Scala by design (Corporate Actions, Settlement,
   Risk, Regulatory Reporting) have no Delta dependency and no placeholder.
2. **Project 10's close-volume divisions will throw under ANSI default on
   a zero-volume/flat-history day — left unguarded by design in the book.**
   The sample data avoids it. Guarding the divisions is a deliberate reader
   decision, not something this extraction changed.

## Status and honesty notes

- **This code has NOT been compiled.** The extraction environment has no
  Java/Scala toolchain, so verification was static only: every `.scala`
  file has balanced braces/parens/brackets, no Markdown fences or prose
  leaked in, and a package declaration matching its directory path.
  Run `sbt compile` / `sbt test` in each project to confirm it builds.
- **Nothing was invented.** Code is copied verbatim from the book. Where
  the book builds a file incrementally, the final version is what is
  here. Where the book's listings are partial excerpts, exercises, or
  prose-only descriptions, no code was fabricated — those cases are
  flagged in each project's README under "Extraction notes".
- **Known book-internal inconsistencies are preserved verbatim**, not
  fixed: see the per-project READMEs for the full lists (examples: a
  duplicate-rule/amendment conflict in Project 2, a survivorship
  CUSIP/ISIN requirement that contradicts Project 6's own tests, and a
  likely `gen.symbols` compile failure in Project 10).
- Some projects' file names were inferred where the book gives no
  `Create …` markers (noted per project); only the folder/file names
  are affected, not the code.
