package com.trading.reporting

import java.time.LocalDate

/** Result of linking one trade to its order history. */
sealed trait LinkResult
object LinkResult {
  case class Linked(enriched: EnrichedTrade) extends LinkResult
  case class Unlinked(
    trade: Trade,
    reason: String
  ) extends LinkResult
  case class OrphanEvents(
    orderId: String,
    events: List[OrderEvent]
  ) extends LinkResult
}

object OrderLinker {

  /**
   * Link trades to their order event histories.
   *
   * Rules:
   *  1. Events are grouped by order identifier.
   *  2. Events within an order are sorted by event timestamp, then by event id
   *     as a deterministic tiebreaker for events sharing a timestamp.
   *  3. A trade links to its order's events only if at least one execution
   *     event exists for that order. A trade with no execution event is
   *     flagged, not silently linked to an empty history.
   *  4. Events for orders with no trade are reported as orphans, so that
   *     operations can investigate whether a trade record is missing.
   */
  def link(
    trades: List[Trade],
    events: List[OrderEvent],
    instruments: Map[String, InstrumentReference],
    parties: Map[String, PartyReference],
    reportingDate: LocalDate
  ): List[LinkResult] = {
    val eventsByOrder: Map[String, List[OrderEvent]] =
      events
        .groupBy(_.orderId)
        .view
        .mapValues(evts => evts.sortBy(e => (e.eventTimestamp, e.eventId)))
        .toMap

    val linkedTrades: List[LinkResult] = trades.map { trade =>
      val orderEvents = eventsByOrder.getOrElse(trade.orderId, List.empty)
      val hasExecution = orderEvents.exists(_.eventType == EventType.OrderExecuted)
      val instrument = instruments.get(trade.symbol)

      (hasExecution, instrument) match {
        case (false, _) =>
          LinkResult.Unlinked(trade, s"No execution event found for order ${trade.orderId}")
        case (_, None) =>
          LinkResult.Unlinked(trade, s"No instrument reference found for symbol ${trade.symbol}")
        case (true, Some(inst)) =>
          val client = trade.clientId.flatMap(parties.get)
          val counterparty = trade.counterpartyId.flatMap(parties.get)
          LinkResult.Linked(
            EnrichedTrade(trade, inst, client, counterparty, orderEvents, reportingDate)
          )
      }
    }

    val tradedOrderIds = trades.map(_.orderId).toSet
    val orphans: List[LinkResult] = eventsByOrder
      .filterNot { case (orderId, _) => tradedOrderIds.contains(orderId) }
      .map { case (orderId, evts) => LinkResult.OrphanEvents(orderId, evts) }
      .toList

    linkedTrades ++ orphans
  }

  /**
   * Reconstruct the lifecycle states of an order from its sorted events.
   * Returns the sequence of (event, state after event) pairs. This is the
   * reconstruction a regulator performs, and producing it internally lets
   * you validate your own reports before submission.
   */
  def reconstructLifecycle(events: List[OrderEvent]): List[(OrderEvent, String)] = {
    val sorted = events.sortBy(e => (e.eventTimestamp, e.eventId))
    var openQuantity: Long = 0L
    var executedQuantity: Long = 0L
    sorted.map { event =>
      val state = event.eventType match {
        case EventType.OrderReceived | EventType.OrderOriginated =>
          openQuantity = event.quantity
          s"OPEN qty=$openQuantity"
        case EventType.OrderRouted =>
          s"ROUTED to ${event.routedVenue.getOrElse("UNKNOWN")} open=$openQuantity"
        case EventType.OrderModified =>
          openQuantity = event.quantity
          s"MODIFIED open=$openQuantity"
        case EventType.OrderCancelled =>
          openQuantity = 0L
          s"CANCELLED executed=$executedQuantity"
        case EventType.OrderExecuted =>
          executedQuantity += event.quantity
          openQuantity = (openQuantity - event.quantity).max(0L)
          s"PARTIALLY_EXECUTED executed=$executedQuantity open=$openQuantity"
      }
      (event, state)
    }
  }
}
