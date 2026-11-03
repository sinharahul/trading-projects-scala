package com.backoffice.capture.enrichment

import com.backoffice.capture.validation.SecurityInfo
import java.sql.DriverManager
import java.time.{LocalDate, LocalTime}
import scala.collection.mutable

case class ReferenceSnapshot(
  securities: Map[String, SecurityInfo],
  approvedCounterparties: Set[String],
  suspendedCounterparties: Set[String],
  tradingDays: Map[(String, LocalDate), (LocalTime, LocalTime)]
)

class ReferenceDataReader(jdbcUrl: String, user: String, password: String) {

  /** Load a full snapshot from Postgres. Called at startup and on refresh. */
  def load(): ReferenceSnapshot = {
    val conn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      val securities = {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(
          "SELECT symbol, exchange, currency, reference_px, status FROM securities")
        val builder = Map.newBuilder[String, SecurityInfo]
        while (rs.next()) {
          builder += rs.getString("symbol") -> SecurityInfo(
            symbol = rs.getString("symbol"),
            exchange = rs.getString("exchange"),
            currency = rs.getString("currency"),
            referencePx = BigDecimal(rs.getBigDecimal("reference_px").toString),
            status = rs.getString("status")
          )
        }
        builder.result()
      }
      val counterparties = {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(
          "SELECT counterparty_id, status FROM counterparties")
        var approved = Set.empty[String]
        var suspended = Set.empty[String]
        while (rs.next()) {
          rs.getString("status") match {
            case "APPROVED" => approved += rs.getString("counterparty_id")
            case "SUSPENDED" => suspended += rs.getString("counterparty_id")
            case _ => // unknown status: treated as not approved by omission
          }
        }
        (approved, suspended)
      }
      val calendar = {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(
          "SELECT exchange, trade_date, is_trading_day, session_open, session_close " +
          "FROM trading_calendar WHERE is_trading_day = TRUE")
        val builder = Map.newBuilder[(String, LocalDate), (LocalTime, LocalTime)]
        while (rs.next()) {
          builder += ((rs.getString("exchange"), rs.getDate("trade_date").toLocalDate) ->
            (rs.getTime("session_open").toLocalTime, rs.getTime("session_close").toLocalTime))
        }
        builder.result()
      }
      ReferenceSnapshot(securities, counterparties._1, counterparties._2, calendar)
    } finally {
      conn.close()
    }
  }
}
