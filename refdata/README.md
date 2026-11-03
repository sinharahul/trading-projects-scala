# Project 6 — Securities Reference Data Master (Golden Copy)

Extracted verbatim from Part 7 (`part-07-project6-reference-data.md`) of the
Spark 4.3.0 edition of the trading back-office book. The book's project
directory name is `refdata` (Step 0: `trading-backoffice/refdata`).
Nothing was pushed to GitHub and the book sources were not modified.

## What it does

Ingests two conflicting vendor feeds (CSV and JSON), links records to the
same security by identifier matching with a conflict check, applies
per-field survivorship rules, stores the result as an SCD Type 2 history,
gates publication on a data-quality scorecard, publishes a Delta current
view plus a Kafka change feed, and answers point-in-time (as-of) queries.

## Layout

- `build.sbt` — project build file, copied verbatim from the book's
  "Build File" section (see the Delta placeholder warning below).
- `project/build.properties` — `sbt.version=1.9.8`, from Part 1.
- `project/plugins.sbt` — no plugin is specified for this project in
  Part 7 or in the Part 1 shared template; the file says so and adds none.
- `src/main/scala/com/trading/refdata/` — `SparkEnv`, `Model`,
  `Validation`, `VendorFeedA`, `VendorFeedB`, `Staging`, `Matching`,
  `Survivorship`, `Scd2Writer`, `DataQuality`, `Publisher`, `ChangeFeed`,
  `PointInTime`, `RefDataPipeline`, `XrefStore`, `KafkaChangePublisher`.
- `src/test/scala/com/trading/refdata/` — `ValidationSpec`,
  `ReferenceDataSpec`.
- `src/main/resources/feeds/vendor_a.csv` (6 rows),
  `src/main/resources/feeds/vendor_b.json` (5 rows).
- `src/main/resources/refdata_xref.sql` — the cross-reference table
  schema from Step 3 (also embedded in `XrefStore.init()`).

## Prerequisites (from the book)

JDK 17 or 21, Scala 2.13.12, sbt 1.9.8, Apache Spark 4.3.0, Delta Lake,
local Postgres, and local Kafka (Part 1 stack).

**Before building, replace the Delta placeholder in `build.sbt`:**
`val deltaVersion = "4.x"` is a placeholder, not a resolvable version.
The book says to fill it with the exact Delta 4.x patch from Delta Lake's
compatibility table for Spark 4.3.0. No patch version was invented here,
so sbt will fail to resolve dependencies until that replacement is made.

## How to run (from the book)

```bash
sbt compile
sbt "testOnly com.trading.refdata.ValidationSpec"
sbt "testOnly com.trading.refdata.ReferenceDataSpec com.trading.refdata.ValidationSpec"
sbt "runMain com.trading.refdata.RefDataPipeline"
```

Expected first-run results, per the book: 6 Vendor A rows + 5 Vendor B
rows staged; 6 survived securities; DQ scorecard total 6, quarantined 1
(Acme); SCD 0 closed / 6 opened / 0 unchanged on first load (but see
Extraction notes — the driver code and this transcript disagree);
published current view 5 rows; change feed 5 `CREATED` events, 0 on an
unchanged second load.

Feed paths in the driver are `refdata/src/main/resources/feeds/...`,
i.e. they assume the working directory is the parent `trading-backoffice`
directory, not this project directory. The Delta table paths
(`data/refdata/staging/vendor_records`,
`data/refdata/golden/security_master`,
`data/refdata/published/security_current`) are relative to the working
directory.

Verify the cross-reference table after two runs (Postgres):

```sql
SELECT id_type, COUNT(*) FROM refdata_xref GROUP BY id_type ORDER BY id_type;
```

Consume the change feed (Kafka):

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic refdata.security.changes --from-beginning
```

## Extraction notes — gaps, ambiguities, and inconsistencies (not fixed)

No code was invented, completed, or "fixed" to resolve these; everything
below is copied as the book presents it.

1. **No incremental/ambiguous final versions.** No main source file is
   shown twice in a later, extended form, so every `.scala` file here is
   the single complete listing the book gives for it. The sector rule is
   the one prose-level exception: Step 4's rule table says "sector:
   voting, tie-break A", the following prose refines it to a tie-break
   to Vendor B, and the code uses Vendor B. The code follows the refined
   prose, not the table.
2. **File names the book's Directory Layout does not name.**
   The layout lists `Publisher.scala` for Step 7 but not a file for the
   `ChangeFeed` object, and it names no file at all for the later wiring
   sections. Extraction choice, flagged here: `ChangeFeed` went into
   `ChangeFeed.scala`, the Postgres wiring into `XrefStore.scala`, and
   the Kafka wiring into `KafkaChangePublisher.scala`, each with the
   book's package `com.trading.refdata`. The SQL schema had no file path
   in the book; it was placed in `src/main/resources/refdata_xref.sql`.
3. **Kafka dependency missing from the verbatim `build.sbt`.**
   `KafkaChangePublisher.scala` imports
   `org.apache.kafka.clients.producer.*`, but the book's `build.sbt`
   for this project lists no `kafka-clients` dependency (only Spark,
   Delta, uJSON, Postgres, ScalaTest). Against the verbatim build file
   that file cannot compile. The dependency was not added, because the
   task requires the build file verbatim.
4. **`Survivorship.survive` requires CUSIP and ISIN — contradicting the
   book's prose, sample data, and tests.** `cusip` and `isin` are built
   with `req(...)`, which throws `IllegalStateException` when no valid
   value survives. Yet Step 4 prose says a security whose only CUSIP is
   invalid "survives with no CUSIP"; the sample rows A-1005 (Old Ticker)
   and A-1006 (Acme) have no valid CUSIP or ISIN; and the tests in
   `ReferenceDataSpec` call `survive` on records built by a helper that
   sets `isin = None` (the "never let an invalid identifier win", SCD
   immaterial-difference, and both DQ tests), and expect a survived
   record with `cusip.value == None`. As written, those calls throw on
   the CUSIP/ISIN requirement before any assertion runs, and the
   driver's sample load would fail on Old Ticker/Acme instead of
   producing the book's "Survived securities: 6" transcript.
5. **Driver vs. transcript and driver vs. matcher.**
   `RefDataPipeline` groups records by shared valid identifier
   (`groupBySharedIdentifier`) and never calls `Matching.link`, so the
   B-2005 trap row is grouped with Apple instead of being quarantined
   by the conflict check — the book itself calls this a "simplified
   in-memory grouping" and says the full implementation splits such
   groups. The driver also never calls `XrefStore.recordGolden`,
   `ChangeFeed`, or `KafkaChangePublisher`, yet the book's end-to-end
   transcript prints "Change feed events: 5 CREATED". And the driver
   writes only non-quarantined securities to the SCD store, so its SCD
   "opened" count cannot be 6 while the published view is 5, as the
   transcript claims (0 closed / 6 opened / 5 published).
6. **Golden keys in the driver are not the book's golden keys.**
   Step 1 says the golden key is a master-assigned UUID, stable for the
   security's lifetime. The driver instead uses the first record's
   `vendorSecurityId` in each group (or `GOLDEN-<index>` for groups with
   no valid identifier), which is order-dependent and vendor-derived.
7. **Data-quality validity checks on identifiers cannot fire as written.**
   Survivorship already filters identifiers through the check-digit
   validators, so a survived CUSIP/ISIN/SEDOL is valid by construction;
   `DataQuality.checkRow`'s `validity.cusip` / `validity.isin` blocks
   on survived values are therefore unreachable for those fields, yet
   the book's transcript reports `validity.cusip` and `validity.isin`
   pass rates of 66.7% (failures for the delisted and fabricated rows).
8. **Described but never shown as code (referenced, not extracted).**
   - The SCD correction / backdated-change path (interval splitting,
     `CORRECTION` / `TICKER_CHANGE` reasons): prose only. `Scd2Writer`
     has no correction method, although `ChangeFeed.eventTypeFor`
     handles `"CORRECTION"`.
   - A Spark-scale matching job: Step 3 mentions applying the pure
     matcher "at scale with a Spark job"; no such wrapper is listed.
   - The quarantine table writer, the load log, and the exception queue:
     mentioned in Steps 5–6 and the runbook, but no code is given;
     `DataQuality.scorecard` returns only quarantined keys.
   - The issuer hierarchy and listing tables: `Model.scala` defines
     `Issuer` and `Listing`, but no pipeline code constructs or writes
     them; the pipeline survives only the flat `GoldenSecurity`.
   - A point-in-time test: Step 8's expected output asks for a test
     building the two-row Meta history and asserting four queries; no
     such test appears in the Tests section.
   - A schema check at ingest (Pitfalls: assert expected headers before
     parsing): described, not coded.
9. **Postgres connection mismatch (book-internal).**
   `XrefStore` hardcodes `jdbc:postgresql://localhost:5432/trading`
   with user/password `trading`/`trading`, whereas the Part 1 template
   uses database/user `backoffice`. Use the `trading` values if you run
   this code as written, or align the database to the template.
10. **Other unverified-by-compilation points (code not compiled here).**
    - `Staging.read` decodes the flat staging table (which carries extra
      `load_batch`/`loaded_at` columns) back into `VendorRecord`, whose
      `asOfDate` is a `LocalDate`.
    - `PointInTime.asOfJoin` reads nullable identifier columns with
      `getAs[String]` into non-optional fields of `EnrichedTrade`.
    - `Publisher.publishCurrent` counts the published dataset after
      writing it (recomputation, not a second write, in Spark semantics).
    - Sample-data traps are intentional and preserved verbatim:
      A-1005/A-1006 invalid identifiers, Vendor A's wrong Meta LEI,
      Alphabet's differing SEDOL, Vendor B's B-2005 identifier-reuse row.
11. **Expected-output snippets excluded, deliberately.** The short REPL
    snippets in "Expected output after Step 2" and similar sections are
    not files and were not turned into source files. Likewise the
    one-line seeding query in the wiring section is an example query,
    not a file; the full schema is in `refdata_xref.sql`.

## Verification performed (no compilation)

- Every `.scala` file: braces, parentheses, and brackets balance by
  raw count; no markdown fences or prose leaked in; every file declares
  `package com.trading.refdata` and lives under
  `src/main|test/scala/com/trading/refdata/`.
- All writes were confined to this `refdata/` directory; the book
  sources were read only, never edited.
- **The code was not compiled**: no Java/Scala toolchain is available
  in this VM, and the Delta `4.x` placeholder in `build.sbt` must be
  replaced first in any case (see above).
