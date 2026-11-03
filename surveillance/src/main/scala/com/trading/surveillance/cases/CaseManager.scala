package com.trading.surveillance.cases

import io.delta.tables.DeltaTable
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.util.UUID

class CaseManager(
  spark: SparkSession,
  casesPath: String,
  notesPath: String,
  auditPath: String
) {
  import spark.implicits._

  private def now(): Timestamp = new Timestamp(System.currentTimeMillis())

  /** Open a case from one or more alerts. Returns the new case id. */
  def openCase(
    alertIds: Seq[String],
    detector: String,
    symbol: String,
    accountIds: Seq[String],
    ownerIds: Seq[String]
  ): String = {
    val caseId = UUID.randomUUID().toString
    val c = Case(
      caseId = caseId,
      alertIds = alertIds,
      detector = detector,
      symbol = symbol,
      accountIds = accountIds,
      beneficialOwnerIds = ownerIds,
      status = "OPEN",
      createdAt = now(),
      updatedAt = now()
    )
    Seq(c).toDF().write.format("delta").mode("append").save(casesPath)
    audit(caseId, "CASE_OPENED", "system",
      s"Case opened from alerts ${alertIds.mkString(",")}")
    caseId
  }

  /** Add an analyst note. Notes are append-only. */
  def addNote(caseId: String, analyst: String, text: String): Unit = {
    val note = CaseNote(
      noteId = UUID.randomUUID().toString,
      caseId = caseId,
      analyst = analyst,
      noteText = text,
      createdAt = now()
    )
    Seq(note).toDF().write.format("delta").mode("append").save(notesPath)
    audit(caseId, "NOTE_ADDED", analyst, s"Note added (${text.take(80)})")
  }

  /** Assign a case to an analyst. */
  def assign(caseId: String, analyst: String): Unit = {
    updateStatus(caseId, "UNDER_INVESTIGATION", analyst,
      s"Assigned to $analyst")
    val table = DeltaTable.forPath(spark, casesPath)
    table.update(
      col("caseId") === caseId,
      Map("assignedAnalyst" -> lit(analyst), "updatedAt" -> lit(now()))
    )
  }

  /** Close a case with a disposition. This is the only way a case leaves the
    * active queue, and the disposition is mandatory. */
  def close(
    caseId: String, analyst: String, disposition: String
  ): Unit = {
    require(
      Set("FALSE_POSITIVE", "NO_ACTION", "ESCALATED",
        "REFERRED_TO_COMPLIANCE").contains(disposition),
      s"Invalid disposition: $disposition"
    )
    val table = DeltaTable.forPath(spark, casesPath)
    val closedStatus =
      if (disposition == "REFERRED_TO_COMPLIANCE") "REFERRED" else "CLOSED"
    table.update(
      col("caseId") === caseId,
      Map(
        "status" -> lit(closedStatus),
        "disposition" -> lit(disposition),
        "updatedAt" -> lit(now())
      )
    )
    audit(caseId, "DISPOSITION_SET", analyst,
      s"Case closed with disposition $disposition")
  }

  /** List open cases, highest priority first. Priority is a placeholder for
    * the scored alert's score; in the full system the case carries the max
    * score of its alerts, and the queue is ordered by it. */
  def openQueue(): DataFrame = {
    spark.read.format("delta").load(casesPath)
      .filter(col("status").isin("OPEN", "UNDER_INVESTIGATION"))
      .orderBy(col("createdAt").asc)
  }

  private def updateStatus(
    caseId: String, status: String, analyst: String, detail: String
  ): Unit = {
    val table = DeltaTable.forPath(spark, casesPath)
    table.update(
      col("caseId") === caseId,
      Map("status" -> lit(status), "updatedAt" -> lit(now()))
    )
    audit(caseId, "STATUS_CHANGED", analyst, detail)
  }

  private def audit(
    caseId: String, eventType: String, analyst: String, detail: String
  ): Unit = {
    val event = AuditEvent(
      eventId = UUID.randomUUID().toString,
      caseId = caseId,
      eventType = eventType,
      analyst = analyst,
      detail = detail,
      createdAt = now()
    )
    Seq(event).toDF().write.format("delta").mode("append").save(auditPath)
  }
}
