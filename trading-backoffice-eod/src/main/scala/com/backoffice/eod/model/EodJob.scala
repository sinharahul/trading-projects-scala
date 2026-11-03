package com.backoffice.eod.model

/** Failures that may resolve on retry: network blips, lock waits,
  * momentary unavailability of a dependency service. */
final class TransientFailure(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)

/** Failures that will not resolve on retry: failed data quality
  * gates, schema mismatches, ledger imbalances, missing reference
  * data. Retrying these wastes the batch window. */
final class PermanentFailure(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)

package com.backoffice.eod.model

import java.time.LocalDate

/** A single EOD job. Implementations do one thing and report
  * how many rows they processed, so gates and run history have
  * something concrete to check.
  */
trait EodJob {
  def name: String
  def dependencies: Seq[String] = Seq.empty
  def maxRetries: Int = 3

  /** Runs the job for the given business date.
    * @return rows processed (rows written, or rows checked for gates)
    * @throws TransientFailure if a retry may help
    * @throws PermanentFailure if a retry will not help
    */
  def run(businessDate: LocalDate): Long
}

/** A data quality gate is a job whose only output is a pass/fail
  * verdict. Gates are jobs, so they participate in dependency
  * ordering and run history exactly like compute jobs.
  */
trait Gate extends EodJob {
  /** Throws PermanentFailure with a descriptive message if the
    * gate fails; returns the number of rows checked otherwise. */
  def check(businessDate: LocalDate): Long
  final override def run(businessDate: LocalDate): Long = check(businessDate)
}
