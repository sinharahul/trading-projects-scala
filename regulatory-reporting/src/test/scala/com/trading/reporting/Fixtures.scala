package com.trading.reporting

import java.time.{Instant, LocalDate}

object Fixtures {
  val reportingDate: LocalDate = LocalDate.of(2026, 10, 31)
  val t0: Instant = Instant.parse("2026-10-30T13:30:00.100Z")

  val aaplRef = InstrumentReference(
    "AAPL", Some("US0378331005"), InstrumentType.Equity,
    "USD", "NASDAQ", isEligibleForCat = true, isEligibleForMifir = true
  )
  val optionRef = InstrumentReference(
    "AAPL261120C00175000", Some("US0378331005"), InstrumentType.EquityOption,
    "USD", "CBOE", isEligibleForCat = true, isEligibleForMifir = true
  )
  val clientRef = PartyReference(
    "CLIENT-1", "John Doe", None, Some("US19400101JOHN_DOE"), "US",
    isNaturalPerson = true
  )
  val counterpartyRef = PartyReference(
    "CP-9", "Counterparty Nine LLC", Some("549300ABCDEF12345678"), None, "US",
    isNaturalPerson = false
  )

  val trade1 = Trade(
    "TRD-1", "ORD-1", "FIRM-A", InstrumentType.Equity, "AAPL",
    Some("US0378331005"), Side.Buy, 800, BigDecimal("175.25"),
    Instant.parse("2026-10-30T13:30:02.000Z"), "NASDAQ", Capacity.Agency,
    Some("CLIENT-1"), Some("DM-42"), Some("TR-7"), Some("CP-9"),
    isShortSale = false, LocalDate.of(2026, 11, 2)
  )

  val trade2 = Trade(
    "TRD-2", "ORD-2", "FIRM-A", InstrumentType.EquityOption,
    "AAPL261120C00175000", Some("US0378331005"), Side.SellShort, 50,
    BigDecimal("4.80"), Instant.parse("2026-10-30T14:05:00.000Z"), "CBOE",
    Capacity.Principal, None, Some("DM-42"), Some("TR-7"), Some("CP-9"),
    isShortSale = true, LocalDate.of(2026, 11, 2)
  )

  val trade3 = Trade(
    "TRD-3", "ORD-3", "FIRM-A", InstrumentType.Equity, "AAPL",
    Some("US0378331005"), Side.Buy, 100, BigDecimal("175.30"),
    Instant.parse("2026-10-30T15:00:00.000Z"), "NASDAQ", Capacity.Agency,
    Some("CLIENT-1"), None, Some("TR-7"), Some("CP-9"),
    isShortSale = false, LocalDate.of(2026, 11, 2)
  )

  def eventsFor(orderId: String, symbol: String): List[OrderEvent] = List(
    OrderEvent("E1", orderId, "FIRM-A", EventType.OrderReceived, t0, symbol, Side.Buy, 1000, Some(BigDecimal("175.00")), None, None),
    OrderEvent("E2", orderId, "FIRM-A", EventType.OrderRouted, t0.plusMillis(150), symbol, Side.Buy, 1000, None, Some("NASDAQ"), None),
    OrderEvent("E3", orderId, "FIRM-A", EventType.OrderExecuted, t0.plusMillis(1900), symbol, Side.Buy, 800, Some(BigDecimal("175.25")), Some("NASDAQ"), None)
  )

  val instruments: Map[String, InstrumentReference] = Map(
    "AAPL" -> aaplRef,
    "AAPL261120C00175000" -> optionRef
  )
  val parties: Map[String, PartyReference] = Map(
    "CLIENT-1" -> clientRef,
    "CP-9" -> counterpartyRef
  )

  def enrichedAll: List[LinkResult] =
    OrderLinker.link(
      List(trade1, trade2, trade3),
      eventsFor("ORD-1", "AAPL") ++
        eventsFor("ORD-2", "AAPL261120C00175000") ++
        eventsFor("ORD-3", "AAPL"),
      instruments, parties, reportingDate
    )

  def enrichedLinked: List[EnrichedTrade] =
    enrichedAll.collect { case LinkResult.Linked(e) => e }
}
