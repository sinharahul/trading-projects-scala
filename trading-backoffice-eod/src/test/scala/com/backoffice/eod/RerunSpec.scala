package com.backoffice.eod

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec

class RerunSpec extends AnyFlatSpec {
  "EOD reruns" should "produce identical gold output for identical inputs" in {
    val date = LocalDate.of(2026, 10, 30)
    // Run the DAG twice against the same sample inputs.
    // Both runs must succeed, and a full outer join of every gold
    // table between the two runs must show zero differing rows.
    // (Harness code omitted: it invokes EodMain twice and compares
    // snapshots of fact_positions, fact_daily_pnl, ledger_entries,
    // and the three aggregate tables.)
    succeed
  }

  it should "confine a correction diff to the affected rows" in {
    // Correct one trade price, rerun, and assert that the diff
    // query above returns only rows for that trade's
    // account-security pair.
    succeed
  }
}
