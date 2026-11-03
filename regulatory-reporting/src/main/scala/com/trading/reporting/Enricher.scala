package com.trading.reporting

import java.time.LocalDate

/** An enrichment failure: a trade that could not be enriched, with a reason. */
case class EnrichmentFailure(
  tradeId: String,
  reason: String
)

object Enricher {

  /**
   * Enrich trades with instrument and party reference data.
   * Returns enriched trades and failures separately. A trade with a
   * missing instrument reference fails; a trade with a missing party
   * reference enriches with the party as None, because the builders
   * and validators handle missing parties explicitly.
   */
  def enrich(
    trades: List[Trade],
    orderEventsByOrder: Map[String, List[OrderEvent]],
    instruments: Map[String, InstrumentReference],
    parties: Map[String, PartyReference],
    reportingDate: LocalDate
  ): (List[EnrichedTrade], List[EnrichmentFailure]) = {
    val results = trades.map { trade =>
      instruments.get(trade.symbol) match {
        case None =>
          Right(EnrichmentFailure(trade.tradeId,
            s"No instrument reference for symbol ${trade.symbol}"))
        case Some(inst) =>
          val client = trade.clientId.flatMap(parties.get)
          val counterparty = trade.counterpartyId.flatMap(parties.get)
          val events = orderEventsByOrder.getOrElse(trade.orderId, List.empty)
            .sortBy(e => (e.eventTimestamp, e.eventId))
          Left(EnrichedTrade(trade, inst, client, counterparty, events, reportingDate))
      }
    }
    val enriched = results.collect { case Left(e) => e }
    val failures = results.collect { case Right(f) => f }
    (enriched, failures)
  }
}
