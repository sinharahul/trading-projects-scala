package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class PositionEngineSpec extends AnyFlatSpec with Matchers {
  private def ts(s: String) = Timestamp.valueOf(s)
  private val now = ts("2026-10-30 09:30:00")

  private def trade(id: String, side: Side, qty: Long, price: String) =
    Trade(id, "ACC-1001", "XYZ", side, qty, BigDecimal(price), now, "TEST")

  "The average-cost engine" should "reproduce the canonical worked example" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)

    // Buy 100 @ $50 -> 100 long, avg cost 50.
    p = PositionEngine.applyTradeAverageCost(p, trade("T1", Buy, 100, "50"))
    p.quantity shouldBe 100
    p.averageCost shouldBe BigDecimal("50.000000")

    // Buy 100 @ $60 -> 200 long, avg cost 55.
    p = PositionEngine.applyTradeAverageCost(p, trade("T2", Buy, 100, "60"))
    p.quantity shouldBe 200
    p.averageCost shouldBe BigDecimal("55.000000")

    // Sell 150 @ $70 -> 50 long, avg cost 55, realized P&L 2250.
    p = PositionEngine.applyTradeAverageCost(p, trade("T3", Sell, 150, "70"))
    p.quantity shouldBe 50
    p.averageCost shouldBe BigDecimal("55.000000")
    p.realizedPnl shouldBe BigDecimal("2250.00")

    // Sell the remaining 50 @ $72 -> flat, realized P&L 2250 + 850 = 3100.
    p = PositionEngine.applyTradeAverageCost(p, trade("T4", Sell, 50, "72"))
    p.isFlat shouldBe true
    p.realizedPnl shouldBe BigDecimal("3100.00")
  }

  it should "flip a long position into a short position correctly" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = PositionEngine.applyTradeAverageCost(p, trade("T1", Buy, 100, "50"))
    // Sell 150 @ $60: closes 100 long (realized 100 * (60 - 50) = 1000),
    // opens 50 short at 60.
    p = PositionEngine.applyTradeAverageCost(p, trade("T2", Sell, 150, "60"))
    p.quantity shouldBe -50
    p.averageCost shouldBe BigDecimal("60.000000")
    p.realizedPnl shouldBe BigDecimal("1000.00")
  }
}
