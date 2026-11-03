package com.trading.surveillance.model

import java.sql.Timestamp

/** A single detector finding. Detectors produce alerts; the scoring layer
  * decides what happens to them.
  *
  * @param alertId unique alert identifier
  * @param detector one of WASH_TRADE, SPOOFING, MARKING_CLOSE, UNUSUAL_VOLUME
  * @param symbol the instrument involved
  * @param accountIds accounts implicated, in deterministic order
  * @param beneficialOwnerIds owners implicated
  * @param windowStart start of the detection window
  * @param windowEnd end of the detection window
  * @param evidence human-readable summary plus machine-readable detail; the
  *                 analyst reads this first
  * @param evidenceOrderIds order ids that constitute the evidence
  * @param evidenceTradeIds trade ids that constitute the evidence
  * @param rawScore the detector's own confidence, 0–100, before scoring
  * @param detectedAt processing timestamp
  */
case class Alert(
  alertId: String,
  detector: String,
  symbol: String,
  accountIds: Seq[String],
  beneficialOwnerIds: Seq[String],
  windowStart: Timestamp,
  windowEnd: Timestamp,
  evidence: String,
  evidenceOrderIds: Seq[String],
  evidenceTradeIds: Seq[String],
  rawScore: Int,
  detectedAt: Timestamp
)

/** An alert after scoring and suppression.
  *
  * @param score final score, 0–100
  * @param status NEW, SUPPRESSED, AUTO_CLOSED, or QUEUED
  * @param suppressionReason why a suppressed alert was suppressed, if any
  */
case class ScoredAlert(
  alert: Alert,
  score: Int,
  status: String,
  suppressionReason: Option[String] = None
)
