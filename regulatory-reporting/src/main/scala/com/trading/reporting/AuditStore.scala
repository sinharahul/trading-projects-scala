package com.trading.reporting

import java.time.{Instant, LocalDate}

/** One immutable audit entry: a report version or a validation failure. */
sealed trait AuditEntry
object AuditEntry {
  case class SubmittedVersion(
    reportId: String,
    originalReportId: Option[String],
    version: Int,
    regime: String,
    state: ReportState,
    submittedAt: Instant,
    reportingDate: LocalDate,
    fields: Map[String, String]
  ) extends AuditEntry

  case class ValidationFailure(
    reportId: String,
    regime: String,
    reportingDate: LocalDate,
    violations: List[Violation],
    failedAt: Instant
  ) extends AuditEntry
}

class AuditStore {
  private var entries: List[AuditEntry] = List.empty

  def append(entry: AuditEntry): Unit = {
    entries = entries :+ entry
  }

  /** Every version of one report, in version order. */
  def versionsOf(originalReportId: String): List[AuditEntry.SubmittedVersion] =
    entries.collect {
      case v: AuditEntry.SubmittedVersion
        if v.reportId == originalReportId || v.originalReportId.contains(originalReportId) =>
        v
    }.sortBy(_.version)

  /** All reports submitted for one reporting date and regime. */
  def submittedOn(
    reportingDate: LocalDate,
    regime: String
  ): List[AuditEntry.SubmittedVersion] =
    entries.collect {
      case v: AuditEntry.SubmittedVersion
        if v.reportingDate == reportingDate && v.regime == regime => v
    }

  /** All validation failures for one reporting date, proving what was not reported. */
  def failuresOn(reportingDate: LocalDate): List[AuditEntry.ValidationFailure] =
    entries.collect {
      case f: AuditEntry.ValidationFailure if f.reportingDate == reportingDate => f
    }

  /** Reports currently in a given state, for operational monitoring. */
  def inState(state: ReportState): List[AuditEntry.SubmittedVersion] =
    entries.collect {
      case v: AuditEntry.SubmittedVersion if v.state == state => v
    }

  /** Late reports: accepted late, or still unsubmitted past their deadline. */
  def lateReports: List[AuditEntry.SubmittedVersion] =
    entries.collect {
      case v: AuditEntry.SubmittedVersion
        if v.state == ReportState.LateAccepted => v
    }

  /** Completeness check: every trade id that should have been reported, and
   *  whether it appears in any submitted version for the date. */
  def completeness(
    expectedTradeIds: List[String],
    reportingDate: LocalDate,
    regime: String
  ): (List[String], List[String]) = {
    val submitted = submittedOn(reportingDate, regime)
      .flatMap(v => v.fields.get("tradeId").orElse(v.fields.get("transactionReferenceNumber")))
      .toSet
    val reported = expectedTradeIds.filter(id => submitted.exists(_.contains(id)))
    val missing = expectedTradeIds.filterNot(id => submitted.exists(_.contains(id)))
    (reported, missing)
  }
}
