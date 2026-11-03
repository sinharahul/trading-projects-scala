package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class MarkToMarketSpec extends AnyFlatSpec with Matchers {
  private val now = Timestamp.valueOf("2026-10-30 09:30:00")
  private def trade(id: String, side: Side, qty: Long, price: String) =
    Trade(id, "ACC-1001", "XYZ", side, qty, BigDecimal(price), now, "TEST")

  "Mark-to-market" should "compute the canonical unrealized P&L" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = AverageCostMethod.applyTrade(p, trade("T1", Buy, 100, "50"))
    p = AverageCostMethod.applyTrade(p, trade("T2", Buy, 100, "60"))
    p = AverageCostMethod.applyTrade(p, trade("T3", Sell, 150, "70"))
    MarkToMarket.unrealizedPnl(p, BigDecimal("72")) shouldBe BigDecimal("850.00")
    MarkToMarket.marketValue(p, BigDecimal("72")) shouldBe BigDecimal("3600.00")
  }

  it should "profit a winning short and lose on a losing short" in {
    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = AverageCostMethod.applyTrade(p, trade("T1", Sell, 100, "30"))
    MarkToMarket.unrealizedPnl(p, BigDecimal("20")) shouldBe BigDecimal("1000.00")
    MarkToMarket.unrealizedPnl(p, BigDecimal("45")) shouldBe BigDecimal("-1500.00")
    MarkToMarket.marketValue(p, BigDecimal("20")) shouldBe BigDecimal("-2000.00")
  }

  it should "refuse to invent a price for an unpriced position" in {
    val p = PositionState.empty("ACC-1001", "XYZ", now)
    MarkToMarket.enrich(p, None, now, "INTRADAY") shouldBe None
  }
}
