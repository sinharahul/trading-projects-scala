package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class FifoSpec extends AnyFlatSpec with Matchers {
  private def ts(s: String) = Timestamp.valueOf(s)
  private val now = ts("2026-10-30 09:30:00")
  private def trade(id: String, side: Side, qty: Long, price: String) =
    Trade(id, "ACC-1001", "XYZ", side, qty, BigDecimal(price), now, "TEST")

  "FIFO" should "reproduce the canonical example with lot-level arithmetic" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = FifoMethod.applyTrade(p, trade("T1", Buy, 100, "50"))
    p = FifoMethod.applyTrade(p, trade("T2", Buy, 100, "60"))
    p.lots shouldBe List(Lot(100, BigDecimal("50")), Lot(100, BigDecimal("60")))

    p = FifoMethod.applyTrade(p, trade("T3", Sell, 150, "70"))
    p.quantity shouldBe 50
    p.lots shouldBe List(Lot(50, BigDecimal("60")))
    p.realizedPnl shouldBe BigDecimal("2500.00")
    p.averageCost shouldBe BigDecimal("60.000000")
  }

  it should "handle partial lot consumption without disturbing later lots" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = FifoMethod.applyTrade(p, trade("T1", Buy, 100, "50"))
    p = FifoMethod.applyTrade(p, trade("T2", Buy, 100, "60"))
    p = FifoMethod.applyTrade(p, trade("T3", Buy, 100, "70"))
    // Sell 120: consumes 100 at 50 and 20 at 60. Basis 5000 + 1200 = 6200.
    // Proceeds at 80: 9600. Realized: 3400.
    p = FifoMethod.applyTrade(p, trade("T4", Sell, 120, "80"))
    p.lots shouldBe List(Lot(80, BigDecimal("60")), Lot(100, BigDecimal("70")))
    p.realizedPnl shouldBe BigDecimal("3400.00")
  }

  it should "agree with average cost on a single-lot sequence" in {
    // With only one lot, FIFO and average cost must agree exactly.
    def run(method: CostBasisMethod): PositionState = {
      var p = PositionState.empty("ACC-1001", "XYZ", now)
      p = method.applyTrade(p, trade("T1", Buy, 100, "50"))
      p = method.applyTrade(p, trade("T2", Sell, 40, "70"))
      p
    }
    val fifo = run(FifoMethod)
    val avg = run(AverageCostMethod)
    fifo.realizedPnl shouldBe avg.realizedPnl // both 800.00
    fifo.quantity shouldBe avg.quantity       // both 60
  }
}
