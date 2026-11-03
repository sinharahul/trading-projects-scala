package com.trading.reporting

import java.time.Instant

object LinkerDemo {
  def main(args: Array[String]): Unit = {
    val base = Instant.parse("2026-10-30T13:30:00.100Z")
    val events = List(
      OrderEvent("E5", "ORD-1", "FIRM-A", EventType.OrderExecuted, base.plusMillis(1900), "AAPL", Side.Buy, 300, Some(BigDecimal("175.25")), Some("NASDAQ"), None),
      OrderEvent("E1", "ORD-1", "FIRM-A", EventType.OrderReceived, base, "AAPL", Side.Buy, 1000, Some(BigDecimal("175.00")), None, None),
      OrderEvent("E2", "ORD-1", "FIRM-A", EventType.OrderRouted, base.plusMillis(150), "AAPL", Side.Buy, 1000, None, Some("NASDAQ"), None),
      OrderEvent("E3", "ORD-1", "FIRM-A", EventType.OrderModified, base.plusMillis(900), "AAPL", Side.Buy, 800, Some(BigDecimal("175.10")), None, None),
      OrderEvent("E4", "ORD-1", "FIRM-A", EventType.OrderExecuted, base.plusMillis(1400), "AAPL", Side.Buy, 500, Some(BigDecimal("175.25")), Some("NASDAQ"), None)
    )
    OrderLinker.reconstructLifecycle(events).foreach { case (event, state) =>
      println(s"${event.eventTimestamp} ${event.eventType} -> $state")
    }
  }
}
