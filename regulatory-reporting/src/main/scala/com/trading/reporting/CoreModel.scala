package com.trading.reporting

import java.time.{Instant, LocalDate}

/** Side of a trade or order. */
sealed trait Side
object Side {
  case object Buy extends Side
  case object Sell extends Side
  case object SellShort extends Side
}

/** Capacity in which the firm acted. */
sealed trait Capacity
object Capacity {
  case object Agency extends Capacity
  case object Principal extends Capacity
  case object RisklessPrincipal extends Capacity
}

/** Instrument type, because equities and options diverge in reporting. */
sealed trait InstrumentType
object InstrumentType {
  case object Equity extends InstrumentType
  case object EquityOption extends InstrumentType
}

/** A validated trade: one execution. This is the atomic input to reporting. */
case class Trade(
  tradeId: String,
  orderId: String,
  firmId: String,
  instrumentType: InstrumentType,
  symbol: String,
  isin: Option[String],
  side: Side,
  quantity: Long,
  price: BigDecimal,
  executionTimestamp: Instant,
  executionVenue: String,
  capacity: Capacity,
  clientId: Option[String],
  decisionMakerId: Option[String],
  executingTraderId: Option[String],
  counterpartyId: Option[String],
  isShortSale: Boolean,
  settlementDate: LocalDate
)

/** A single event in an order lifecycle. */
case class OrderEvent(
  eventId: String,
  orderId: String,
  firmId: String,
  eventType: EventType,
  eventTimestamp: Instant,
  symbol: String,
  side: Side,
  quantity: Long,
  price: Option[BigDecimal],
  routedVenue: Option[String],
  parentOrderId: Option[String]
)

/** The types of reportable order events. */
sealed trait EventType
object EventType {
  case object OrderReceived extends EventType
  case object OrderOriginated extends EventType
  case object OrderRouted extends EventType
  case object OrderModified extends EventType
  case object OrderCancelled extends EventType
  case object OrderExecuted extends EventType
}

/** Reference data for an instrument, joined at enrichment time. */
case class InstrumentReference(
  symbol: String,
  isin: Option[String],
  instrumentType: InstrumentType,
  currency: String,
  exchange: String,
  isEligibleForCat: Boolean,
  isEligibleForMifir: Boolean
)

/** Reference data for a client or counterparty. */
case class PartyReference(
  partyId: String,
  legalName: String,
  lei: Option[String],
  nationalId: Option[String],
  countryCode: String,
  isNaturalPerson: Boolean
)

/** An enriched trade: a validated trade plus everything the builders need. */
case class EnrichedTrade(
  trade: Trade,
  instrument: InstrumentReference,
  client: Option[PartyReference],
  counterparty: Option[PartyReference],
  orderEvents: List[OrderEvent],
  reportingDate: LocalDate
)
