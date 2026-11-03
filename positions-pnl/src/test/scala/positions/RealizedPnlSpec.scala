package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class RealizedPnlSpec extends AnyFlatSpec with Matchers {
  private val now = Timestamp.valueOf("2026-10-30 09:30:00")
  private def trade(id: String, side: Side, qty: Long, price: String) =
    Trade(id, "ACC-1001", "XYZ", side, qty, BigDecimal(price), now, "TEST")

  "Realized P&L" should "follow the six-step scenario exactly" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = AverageCostMethod.applyTrade(p, trade("T1", Buy, 100, "50"))
    p = AverageCostMethod.applyTrade(p, trade("T2", Buy, 100, "60"))
    p = AverageCostMethod.applyTrade(p, trade("T3", Sell, 150, "70"))
    p.realizedPnl shouldBe BigDecimal("2250.00")
    p = AverageCostMethod.applyTrade(p, trade("T4", Sell, 50, "40"))
    p.isFlat shouldBe true
    p.realizedPnl shouldBe BigDecimal("1500.00")
    p = AverageCostMethod.applyTrade(p, trade("T5", Sell, 100, "30"))
    p.quantity shouldBe -100
    p.realizedPnl shouldBe BigDecimal("1500.00")
    p = AverageCostMethod.applyTrade(p, trade("T6", Buy, 100, "20"))
    p.isFlat shouldBe true
    p.realizedPnl shouldBe BigDecimal("2500.00")
  }

  it should "match FIFO on the same scenario with FIFO's own numbers" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = FifoMethod.applyTrade(p, trade("T1", Buy, 100, "50"))
    p = FifoMethod.applyTrade(p, trade("T2", Buy, 100, "60"))
    p = FifoMethod.applyTrade(p, trade("T3", Sell, 150, "70"))
    p.realizedPnl shouldBe BigDecimal("2500.00") // FIFO: basis 8000
    p = FifoMethod.applyTrade(p, trade("T4", Sell, 50, "40"))
    // Remaining FIFO lot is 50 at 60; selling at 40 loses 1000.
    p.realizedPnl shouldBe BigDecimal("1500.00")
  }
}
