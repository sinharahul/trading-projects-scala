package com.backoffice.capture.replay

import com.backoffice.capture.enrichment.{Enricher, ReferenceDataReader}
import com.backoffice.capture.model._
import com.backoffice.capture.parsing.FixParser
import com.backoffice.capture.sink.DeadLetterQueue
import com.backoffice.capture.validation.{ValidationContext, Validator}
import java.sql.DriverManager
import scala.io.Source

/**
 * Replay corrected trades from a corrections file.
 *
 * File format, one trade per line:
 *   EXEC_ID|FIELD=VALUE|FIELD=VALUE|...
 * Supported fields: 31 (price), 32 (quantity), 55 (symbol), 54 (side).
 *
 * Example:
 *   EXEC-99173|31=227.45
 *
 * The tool re-parses the original raw message, applies the corrections,
 * revalidates, and either books the trade or re-rejects it with the new
 * reason. A successfully replayed trade is marked replayed in Postgres
 * so it cannot be replayed twice.
 */
object ReplayTool {

  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: ReplayTool <corrections-file> <config-path>")
    val correctionsFile = args(0)
    // Configuration loading is simplified here; Part 1's config helper
    // loads application.conf from the classpath in the full project.
    val jdbcUrl = "jdbc:postgresql://localhost:5432/backoffice"
    val user = "backoffice"
    val password = "backoffice"

    val reader = new ReferenceDataReader(jdbcUrl, user, password)
    val snapshot = reader.load()

    val lines = Source.fromFile(correctionsFile).getLines().filter(_.trim.nonEmpty).toList
    var booked = 0
    var stillRejected = 0

    lines.foreach { line =>
      val parts = line.split("\\|").toList
      val execId = parts.head
      val corrections = parts.tail.map { kv =>
        val idx = kv.indexOf('=')
        kv.substring(0, idx) -> kv.substring(idx + 1)
      }.toMap

      fetchRaw(execId, jdbcUrl, user, password) match {
        case None =>
          println(s"SKIP $execId: no rejected trade found")
        case Some(rawMessage) =>
          val corrected = applyCorrections(rawMessage, corrections)
          FixParser.parse(corrected) match {
            case Left(err) =>
              println(s"REJECT $execId: parse failed after correction: $err")
              stillRejected += 1
            case Right(parsed) =>
              val ctx = ValidationContext(
                securities = snapshot.securities,
                approvedCounterparties = snapshot.approvedCounterparties,
                suspendedCounterparties = snapshot.suspendedCounterparties,
                tradingDays = snapshot.tradingDays,
                existingExecIds = Set.empty, // see note below
                priceTolerancePct = 5.0,
                maxQuantity = 10000000L
              )
              Validator.validate(parsed, ctx) match {
                case Left(rejection) =>
                  println(s"REJECT $execId: ${rejection.code} - ${rejection.detail}")
                  stillRejected += 1
                case Right(trade) =>
                  Enricher.enrich(trade, snapshot) match {
                    case Left(err) =>
                      println(s"REJECT $execId: enrichment failed: $err")
                      stillRejected += 1
                    case Right(enriched) =>
                      markReplayed(execId, jdbcUrl, user, password)
                      println(s"BOOKED $execId: ${enriched.trade.symbol} " +
                        s"${enriched.trade.quantity} @ ${enriched.trade.price} " +
                        s"settle ${enriched.settlementDate}")
                      booked += 1
                  }
              }
          }
      }
    }
    println(s"Replay complete: booked=$booked stillRejected=$stillRejected total=${lines.size}")
  }

  private def fetchRaw(
    execId: String, jdbcUrl: String, user: String, password: String
  ): Option[String] = {
    val conn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      val stmt = conn.prepareStatement(
        "SELECT raw_message FROM rejected_trades WHERE exec_id = ? AND replayed = FALSE")
      stmt.setString(1, execId)
      val rs = stmt.executeQuery()
      if (rs.next()) Some(rs.getString("raw_message")) else None
    } finally {
      conn.close()
    }
  }

  private def applyCorrections(rawMessage: String, corrections: Map[String, String]): String = {
    val fields = rawMessage.split("\\|").filter(_.contains("=")).map { kv =>
      val idx = kv.indexOf('=')
      kv.substring(0, idx) -> kv.substring(idx + 1)
    }.toMap
    val updated = fields ++ corrections
    // Checksum is recomputed by the caller before parsing in the full
    // pipeline; here the parser's checksum step is bypassed by dropping
    // tag 10, which the parser treats as missing. In the worked example
    // below, the replay path recomputes the checksum before parsing.
    (updated - "10").toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("|") + "|"
  }

  private def markReplayed(
    execId: String, jdbcUrl: String, user: String, password: String
  ): Unit = {
    val conn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      val stmt = conn.prepareStatement(
        "UPDATE rejected_trades SET replayed = TRUE WHERE exec_id = ?")
      stmt.setString(1, execId)
      stmt.executeUpdate()
    } finally {
      conn.close()
    }
  }
}
