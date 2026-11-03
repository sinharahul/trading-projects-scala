package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class FullDaySpec extends AnyFlatSpec with Matchers {
  private val now = Timestamp.valueOf("2026-10-30 09:30:00")
  private def trade(id: String, sym: String, side: Side, qty: Long, price: String) =
    Trade(id, "ACC-2001", sym, side, qty, BigDecimal(price), now, "TEST")

  "A full trading day" should "produce the hand-computed totals" in {
    var aapl = PositionState.empty("ACC-2001", "AAPL", now)
    aapl = AverageCostMethod.applyTrade(aapl, trade("A1", "AAPL", Buy, 500, "175"))
    aapl = AverageCostMethod.applyTrade(aapl, trade("A2", "AAPL", Buy, 300, "180"))
    aapl = AverageCostMethod.applyTrade(aapl, trade("A3", "AAPL", Sell, 600, "190"))
    aapl.realizedPnl shouldBe BigDecimal("7875.00")
    aapl = AverageCostMethod.applyTrade(aapl, trade("A4", "AAPL", Sell, 200, "185"))
    aapl.isFlat shouldBe true
    aapl.realizedPnl shouldBe BigDecimal("9500.00")

    var msft = PositionState.empty("ACC-2001", "MSFT", now)
    msft = AverageCostMethod.applyTrade(msft, trade("M1", "MSFT", Sell, 200, "420"))
    msft = AverageCostMethod.applyTrade(msft, trade("M2", "MSFT", Buy, 100, "400"))
    msft.realizedPnl shouldBe BigDecimal("2000.00")
    msft = AverageCostMethod.applyTrade(msft, trade("M3", "MSFT", Buy, 100, "430"))
    msft.isFlat shouldBe true
    msft.realizedPnl shouldBe BigDecimal("1000.00")
  }

  it should "converge between FIFO and average cost when fully closed" in {
    def runAapl(method: CostBasisMethod): PositionState = {
      var p = PositionState.empty("ACC-2001", "AAPL", now)
      p = method.applyTrade(p, trade("A1", "AAPL", Buy, 500, "175"))
      p = method.applyTrade(p, trade("A2", "AAPL", Buy, 300, "180"))
      p = method.applyTrade(p, trade("A3", "AAPL", Sell, 600, "190"))
      p = method.applyTrade(p, trade("A4", "AAPL", Sell, 200, "185"))
      p
    }
    runAapl(FifoMethod).realizedPnl shouldBe runAapl(AverageCostMethod).realizedPnl
  }
}
