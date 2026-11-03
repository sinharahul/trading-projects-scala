package com.backoffice.capture.enrichment

import com.backoffice.capture.model._
import java.time.LocalDate

object Enricher {

  /**
   * Enrich a validated trade. Returns Left(reason) if enrichment cannot
   * complete — for example, the settlement calendar has no future trading
   * day within the search horizon, which indicates a calendar data problem.
   */
  def enrich(trade: Trade, snapshot: ReferenceSnapshot): Either[String, EnrichedTrade] = {
    snapshot.securities.get(trade.symbol) match {
      case None =>
        Left(s"Enrichment failed: no security master row for ${trade.symbol}")
      case Some(info) =>
        val notional = BigDecimal(trade.quantity) * trade.price
        settlementDate(trade.tradeDate, info.exchange, snapshot) match {
          case None =>
            Left(s"Enrichment failed: no settlement date within 10 days of ${trade.tradeDate}")
          case Some(settle) =>
            Right(EnrichedTrade(
              trade = trade,
              exchange = info.exchange,
              currency = info.currency,
              notional = notional,
              settlementDate = settle
            ))
        }
    }
  }

  /**
   * T+1 settlement: the first trading day strictly after the trade date,
   * for the trade's exchange. Weekends and exchange holidays are skipped
   * because they are absent from the trading calendar.
   */
  def settlementDate(
    tradeDate: LocalDate,
    exchange: String,
    snapshot: ReferenceSnapshot
  ): Option[LocalDate] = {
    var candidate = tradeDate.plusDays(1)
    var checked = 0
    while (checked < 10) {
      if (snapshot.tradingDays.contains((exchange, candidate))) return Some(candidate)
      candidate = candidate.plusDays(1)
      checked += 1
    }
    None
  }
}
