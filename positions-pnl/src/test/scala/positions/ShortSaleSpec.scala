package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class ShortSaleSpec extends AnyFlatSpec with Matchers {
  private val now = Timestamp.valueOf("2026-10-30 09:30:00")
  private def trade(id: String, side: Side, qty: Long, price: String) =
    Trade(id, "ACC-1001", "XYZ", side, qty, BigDecimal(price), now, "TEST")

  "Short sales" should "follow the four-phase scenario exactly" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = AverageCostMethod.applyTrade(p, trade("T1", Sell, 100, "50"))
    p.quantity shouldBe -100
    p.averageCost shouldBe BigDecimal("50.000000")
    p = AverageCostMethod.applyTrade(p, trade("T2", Sell, 100, "60"))
    p.quantity shouldBe -200
    p.averageCost shouldBe BigDecimal("55.000000")
    p = AverageCostMethod.applyTrade(p, trade("T3", Buy, 150, "40"))
    p.quantity shouldBe -50
    p.realizedPnl shouldBe BigDecimal("2250.00")
    p = AverageCostMethod.applyTrade(p, trade("T4", Buy, 50, "70"))
    p.isFlat shouldBe true
    p.realizedPnl shouldBe BigDecimal("1500.00")
  }

  it should "flip a short position into a long position correctly" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = AverageCostMethod.applyTrade(p, trade("T1", Sell, 100, "50"))
    // Buy 150 @ $40: covers 100 short (profit 1000), opens 50 long at 40.
    p = AverageCostMethod.applyTrade(p, trade("T2", Buy, 150, "40"))
    p.quantity shouldBe 50
    p.averageCost shouldBe BigDecimal("40.000000")
    p.realizedPnl shouldBe BigDecimal("1000.00")
  }

  it should "agree between FIFO and average cost once fully covered" in {
    def run(method: CostBasisMethod): PositionState = {
      var p = PositionState.empty("ACC-1001", "XYZ", now)
      p = method.applyTrade(p, trade("T1", Sell, 100, "50"))
      p = method.applyTrade(p, trade("T2", Sell, 100, "60"))
      p = method.applyTrade(p, trade("T3", Buy, 200, "40"))
      p
    }
    val fifo = run(FifoMethod)
    val avg = run(AverageCostMethod)
    fifo.isFlat shouldBe true
    avg.isFlat shouldBe true
    fifo.realizedPnl shouldBe avg.realizedPnl // both 3000.00
  }
}
