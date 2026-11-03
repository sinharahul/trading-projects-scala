package com.trading.surveillance.cases

import java.sql.Timestamp

/** A case is an alert (or a group of related alerts) under human investigation.
  *
  * @param caseId unique case identifier
  * @param alertIds alerts grouped into this case
  * @param detector the primary detector (cases are usually single-detector, but
  *                 related alerts from other detectors can be attached)
  * @param symbol the instrument
  * @param accountIds accounts under investigation
  * @param beneficialOwnerIds owners under investigation
  * @param status OPEN, UNDER_INVESTIGATION, CLOSED, or REFERRED
  * @param disposition set at closure: FALSE_POSITIVE, NO_ACTION, ESCALATED,
  *                    or REFERRED_TO_COMPLIANCE
  * @param assignedAnalyst analyst currently owning the case
  * @param createdAt when the case was opened
  * @param updatedAt last modification
  */
case class Case(
  caseId: String,
  alertIds: Seq[String],
  detector: String,
  symbol: String,
  accountIds: Seq[String],
  beneficialOwnerIds: Seq[String],
  status: String,
  disposition: Option[String] = None,
  assignedAnalyst: Option[String] = None,
  createdAt: Timestamp,
  updatedAt: Timestamp
)

/** One analyst note on a case. Notes are append-only: they are never edited
  * or deleted, because the audit trail must show what the analyst knew and
  * when they knew it.
  */
case class CaseNote(
  noteId: String,
  caseId: String,
  analyst: String,
  noteText: String,
  createdAt: Timestamp
)

/** One audit event: every state change on a case is recorded here.
  *
  * @param eventType CASE_OPENED, NOTE_ADDED, ASSIGNED, STATUS_CHANGED,
  *                  DISPOSITION_SET
  * @param detail free-text detail, e.g. the old and new status
  */
case class AuditEvent(
  eventId: String,
  caseId: String,
  eventType: String,
  analyst: String,
  detail: String,
  createdAt: Timestamp
)
