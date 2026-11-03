package com.trading.reporting

/** Lifecycle states for a submitted report. */
sealed trait ReportState
object ReportState {
  case object Built extends ReportState
  case object Validated extends ReportState
  case object Submitted extends ReportState
  case object Accepted extends ReportState
  case object Rejected extends ReportState
  case object LateAccepted extends ReportState
  case object Corrected extends ReportState
  case object Resubmitted extends ReportState
  case object AcknowledgementMissing extends ReportState
}

/** An acknowledgement from the regulator. */
case class Acknowledgement(
  reportId: String,
  status: AckStatus,
  errorCodes: List[String],
  receivedAt: java.time.Instant
)

sealed trait AckStatus
object AckStatus {
  case object Accepted extends AckStatus
  case object Rejected extends AckStatus
  case object LateAccepted extends AckStatus
}

/** One version of one report, with its full state history. */
case class ReportVersion(
  reportId: String,
  originalReportId: Option[String],
  version: Int,
  state: ReportState,
  stateHistory: List[(ReportState, java.time.Instant, String)],
  rejectionReasons: List[String]
)

object AckProcessor {

  /** Apply an acknowledgement to a report version, returning the new version. */
  def applyAck(
    report: ReportVersion,
    ack: Acknowledgement
  ): ReportVersion = {
    val (newState, reasons) = ack.status match {
      case AckStatus.Accepted =>
        (ReportState.Accepted, List.empty[String])
      case AckStatus.LateAccepted =>
        (ReportState.LateAccepted, List("Filed after deadline"))
      case AckStatus.Rejected =>
        (ReportState.Rejected, ack.errorCodes)
    }
    report.copy(
      state = newState,
      stateHistory = report.stateHistory :+
        (newState, ack.receivedAt, s"Ack ${ack.status} codes=${ack.errorCodes.mkString(",")}"),
      rejectionReasons = if (ack.status == AckStatus.Rejected) ack.errorCodes else report.rejectionReasons
    )
  }

  /**
   * Create a corrected version of a rejected report.
   *
   * Rules:
   *  - The corrected version gets version + 1.
   *  - Its original report id points at the first version, so the full
   *    correction chain is traceable to the original submission.
   *  - The rejected version's state becomes Corrected; it is never deleted.
   *  - The corrected version starts in state Built and must pass through
   *    validation and submission again. Corrections do not skip validation.
   */
  def correct(
    rejected: ReportVersion,
    correctedAt: java.time.Instant,
    correctionReason: String
  ): (ReportVersion, ReportVersion) = {
    val originalId = rejected.originalReportId.getOrElse(rejected.reportId)
    val corrected = ReportVersion(
      reportId = s"${originalId}-v${rejected.version + 1}",
      originalReportId = Some(originalId),
      version = rejected.version + 1,
      state = ReportState.Built,
      stateHistory = List(
        (ReportState.Built, correctedAt, s"Correction of ${rejected.reportId}: $correctionReason")
      ),
      rejectionReasons = List.empty
    )
    val superseded = rejected.copy(
      state = ReportState.Corrected,
      stateHistory = rejected.stateHistory :+
        (ReportState.Corrected, correctedAt, s"Superseded by ${corrected.reportId}")
    )
    (superseded, corrected)
  }

  /**
   * Flag submissions with no acknowledgement after the given window.
   * Returns the report ids that need escalation.
   */
  def missingAcks(
    submitted: List[(String, java.time.Instant)],
    acksReceived: Set[String],
    now: java.time.Instant,
    windowMinutes: Long
  ): List[String] =
    submitted
      .filterNot { case (id, _) => acksReceived.contains(id) }
      .filter { case (_, submittedAt) =>
        java.time.Duration.between(submittedAt, now).toMinutes > windowMinutes
      }
      .map(_._1)
}
