package com.trading.refdata

import java.time.LocalDateTime

/** One change-feed event. `before` is None for a new security. */
case class SecurityChangeEvent(
  schemaVersion: Int,
  eventType: String, // CREATED, UPDATED, CORRECTED, DELISTED
  goldenKey: String,
  effectiveFrom: String,
  changeReason: String,
  before: Option[Map[String, String]],
  after: Map[String, String],
  loadBatch: String,
  eventTime: String
)

object ChangeFeed {
  def eventTypeFor(changeReason: String, status: String): String = {
    if (changeReason == "INITIAL_LOAD") "CREATED"
    else if (changeReason == "CORRECTION") "CORRECTED"
    else if (status.toUpperCase == "DELISTED") "DELISTED"
    else "UPDATED"
  }

  def toEvent(row: GoldenRow, before: Option[GoldenRow],
              loadBatch: String): SecurityChangeEvent = {
    def asMap(r: GoldenRow): Map[String, String] = Map(
      "name" -> r.name,
      "cusip" -> r.cusip.getOrElse(""),
      "isin" -> r.isin.getOrElse(""),
      "ticker" -> r.ticker.getOrElse(""),
      "mic" -> r.mic.getOrElse(""),
      "sector" -> r.sector,
      "status" -> r.status,
      "issuerLei" -> r.issuerLei.getOrElse("")
    )
    SecurityChangeEvent(
      schemaVersion = 1,
      eventType = eventTypeFor(row.changeReason, row.status),
      goldenKey = row.goldenKey,
      effectiveFrom = row.effectiveFrom.toString,
      changeReason = row.changeReason,
      before = before.map(asMap),
      after = asMap(row),
      loadBatch = loadBatch,
      eventTime = LocalDateTime.now().toString
    )
  }

  /** Serialises an event to JSON with uJSON, for the Kafka producer. */
  def toJson(e: SecurityChangeEvent): String = {
    val beforeJson: ujson.Value = e.before match {
      case Some(m) => ujson.Obj.from(m.map { case (k, v) => k -> ujson.Str(v) })
      case None => ujson.Null
    }
    ujson.write(ujson.Obj(
      "schemaVersion" -> ujson.Num(e.schemaVersion),
      "eventType" -> ujson.Str(e.eventType),
      "goldenKey" -> ujson.Str(e.goldenKey),
      "effectiveFrom" -> ujson.Str(e.effectiveFrom),
      "changeReason" -> ujson.Str(e.changeReason),
      "before" -> beforeJson,
      "after" -> ujson.Obj.from(e.after.map { case (k, v) => k -> ujson.Str(v) }),
      "loadBatch" -> ujson.Str(e.loadBatch),
      "eventTime" -> ujson.Str(e.eventTime)
    ))
  }
}
