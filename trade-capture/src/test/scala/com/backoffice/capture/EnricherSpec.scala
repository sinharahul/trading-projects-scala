package com.backoffice.capture

import com.backoffice.capture.enrichment._
import com.backoffice.capture.model._
import com.backoffice.capture.validation.SecurityInfo
import java.time.{LocalDate, LocalTime}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnricherSpec extends AnyFlatSpec with Matchers {

  private val snapshot = ReferenceSnapshot(
    securities = Map(
      "AAPL" -> SecurityInfo("AAPL", "NASDAQ", "USD", BigDecimal("227.50"), "ACTIVE")
    ),
    approvedCounterparties = Set("EXCHANGE_X"),
    suspendedCounterparties = Set.empty,
    tradingDays = Map(
      ("NASDAQ", LocalDate.of(2026, 10, 28)) -> (LocalTime.of(9, 30), LocalTime.of(16, 0)),
      ("NASDAQ", LocalDate.of(2026, 10, 29)) -> (LocalTime.of(9, 30), LocalTime.of(16, 0)),
      ("NASDAQ", LocalDate.of(2026, 10, 30)) -> (LocalTime.of(9, 30), LocalTime.of(16, 0)),
      ("NASDAQ", LocalDate.of(2026, 11, 2)) -> (LocalTime.of(9, 30), LocalTime.of(16, 0))
    )
  )

  private def trade(date: LocalDate): Trade = Trade(
    "EXEC-1", "ORD-1", "AAPL", Side.Buy, ExecType.Trade,
    2500L, BigDecimal("227.45"), date, "EXCHANGE_X"
  )

  "Enricher" should "compute notional exactly in BigDecimal" in {
    val enriched = Enricher.enrich(trade(LocalDate.of(2026, 10, 28)), snapshot)
    enriched.map(_.notional) shouldBe Right(BigDecimal("568625.00"))
  }

  it should "settle a Wednesday trade on Thursday" in {
    val enriched = Enricher.enrich(trade(LocalDate.of(2026, 10, 28)), snapshot)
    enriched.map(_.settlementDate) shouldBe Right(LocalDate.of(2026, 10, 29))
  }

  it should "skip the weekend for a Friday trade" in {
    val enriched = Enricher.enrich(trade(LocalDate.of(2026, 10, 30)), snapshot)
    enriched.map(_.settlementDate) shouldBe Right(LocalDate.of(2026, 11, 2))
  }

  it should "attach exchange and currency from the security master" in {
    val enriched = Enricher.enrich(trade(LocalDate.of(2026, 10, 28)), snapshot)
    enriched.map(e => (e.exchange, e.currency)) shouldBe Right(("NASDAQ", "USD"))
  }

  it should "fail enrichment for an unknown symbol" in {
    val unknown = trade(LocalDate.of(2026, 10, 28)).copy(symbol = "NOPE")
    Enricher.enrich(unknown, snapshot).isLeft shouldBe true
  }
}
