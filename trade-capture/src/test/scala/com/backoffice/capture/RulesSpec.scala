package com.backoffice.capture

import com.backoffice.capture.model._
import com.backoffice.capture.validation._
import java.time.{Instant, LocalDate, LocalTime}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RulesSpec extends AnyFlatSpec with Matchers {

  private val baseCtx = ValidationContext(
    securities = Map(
      "AAPL" -> SecurityInfo("AAPL", "NASDAQ", "USD", BigDecimal("227.50"), "ACTIVE"),
      "DEAD" -> SecurityInfo("DEAD", "NYSE", "USD", BigDecimal("10.00"), "DELISTED")
    ),
    approvedCounterparties = Set("EXCHANGE_X", "EXCHANGE_Y"),
    suspendedCounterparties = Set("BROKER_B"),
    tradingDays = Map(
      ("NASDAQ", LocalDate.of(2026, 10, 30)) -> (LocalTime.of(9, 30), LocalTime.of(16, 0)),
      ("NYSE", LocalDate.of(2026, 10, 30)) -> (LocalTime.of(9, 30), LocalTime.of(16, 0))
    ),
    existingExecIds = Set("EXEC-OLD"),
    priceTolerancePct = 5.0,
    maxQuantity = 10000000L
  )

  private def raw(
    execId: String = "EXEC-1",
    symbol: String = "AAPL",
    qty: Long = 2500L,
    px: BigDecimal = BigDecimal("227.45"),
    time: Instant = Instant.parse("2026-10-30T14:32:10.120Z"),
    sender: String = "EXCHANGE_X"
  ): RawExecution = RawExecution(
    execId, "ORD-1", symbol, Side.Buy, ExecType.Trade,
    10000L, qty, px, time, sender, 1L
  )

  "Validator" should "accept a valid trade" in {
    Validator.validate(raw(), baseCtx).isRight shouldBe true
  }

  it should "reject a zero quantity" in {
    Validator.validate(raw(qty = 0L), baseCtx).left.map(_.code) shouldBe
      Left("INVALID_QUANTITY")
  }

  it should "reject a negative quantity" in {
    Validator.validate(raw(qty = -100L), baseCtx).left.map(_.code) shouldBe
      Left("INVALID_QUANTITY")
  }

  it should "reject a quantity above the maximum" in {
    Validator.validate(raw(qty = 20000000L), baseCtx).left.map(_.code) shouldBe
      Left("INVALID_QUANTITY")
  }

  it should "reject a zero price" in {
    Validator.validate(raw(px = BigDecimal("0")), baseCtx).left.map(_.code) shouldBe
      Left("INVALID_PRICE")
  }

  it should "reject an unknown symbol" in {
    Validator.validate(raw(symbol = "NOPE"), baseCtx).left.map(_.code) shouldBe
      Left("UNKNOWN_SYMBOL")
  }

  it should "reject a delisted symbol" in {
    Validator.validate(raw(symbol = "DEAD"), baseCtx).left.map(_.code) shouldBe
      Left("DELISTED_SYMBOL")
  }

  it should "reject a price outside tolerance" in {
    // Reference is 227.50; 300.00 is ~32% away, far beyond 5%.
    Validator.validate(raw(px = BigDecimal("300.00")), baseCtx).left.map(_.code) shouldBe
      Left("PRICE_OUT_OF_TOLERANCE")
  }

  it should "accept a price just inside tolerance" in {
    // 227.50 * 1.049 = 238.65, inside 5%.
    Validator.validate(raw(px = BigDecimal("238.00")), baseCtx).isRight shouldBe true
  }

  it should "reject a duplicate ExecID" in {
    Validator.validate(raw(execId = "EXEC-OLD"), baseCtx).left.map(_.code) shouldBe
      Left("DUPLICATE_EXEC_ID")
  }

  it should "reject a suspended counterparty" in {
    Validator.validate(raw(sender = "BROKER_B"), baseCtx).left.map(_.code) shouldBe
      Left("COUNTERPARTY_NOT_APPROVED")
  }

  it should "reject an unknown counterparty" in {
    Validator.validate(raw(sender = "VENUE_Z"), baseCtx).left.map(_.code) shouldBe
      Left("COUNTERPARTY_NOT_APPROVED")
  }

  it should "reject a trade outside market hours" in {
    // 22:00 UTC = 18:00 New York, after the 16:00 close.
    val afterHours = Instant.parse("2026-10-30T22:00:00.000Z")
    Validator.validate(raw(time = afterHours), baseCtx).left.map(_.code) shouldBe
      Left("OUTSIDE_TRADING_HOURS")
  }

  it should "reject a trade on a non-trading day" in {
    // October 31 is not in the trading calendar.
    val saturday = Instant.parse("2026-10-31T14:32:10.120Z")
    Validator.validate(raw(time = saturday), baseCtx).left.map(_.code) shouldBe
      Left("OUTSIDE_TRADING_HOURS")
  }

  it should "accept a trade at 13:00 UTC, which is 09:00 New York and before the open — no, reject it" in {
    // This test name documents the timezone trap explicitly.
    // 13:00 UTC = 09:00 New York, thirty minutes before the 09:30 open.
    // A naive UTC comparison would wrongly accept it.
    val beforeOpen = Instant.parse("2026-10-30T13:00:00.000Z")
    Validator.validate(raw(time = beforeOpen), baseCtx).left.map(_.code) shouldBe
      Left("OUTSIDE_TRADING_HOURS")
  }

  it should "report exactly one rejection for a missing symbol" in {
    // priceTolerance and tradingHours must not pile on extra reasons.
    val failures = Validator.validateAll(raw(symbol = "NOPE"), baseCtx)
    failures.map(_.code) shouldBe List("UNKNOWN_SYMBOL")
  }
}
