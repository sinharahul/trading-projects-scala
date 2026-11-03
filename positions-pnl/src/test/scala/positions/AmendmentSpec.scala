package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class AmendmentSpec extends AnyFlatSpec with Matchers {
  private val now = Timestamp.valueOf("2026-10-30 09:30:00")

  "Versioned trades" should "resolve amendments and cancels correctly" in {
    val v1 = VersionedTrade(
      Trade("T1", "ACC-1001", "XYZ", Buy, 100, BigDecimal("50"), now, "FIX"),
      1, Active)
    val v2 = VersionedTrade(
      Trade("T1", "ACC-1001", "XYZ", Buy, 100, BigDecimal("55"), now, "FIX"),
      2, Active)
    val cancelled = VersionedTrade(
      Trade("T2", "ACC-1001", "XYZ", Sell, 50, BigDecimal("70"), now, "FIX"),
      1, Cancelled)

    val effective = VersionedTrades.effectiveTrades(List(v1, v2, cancelled))
    effective should have size 1
    effective.head.price shouldBe BigDecimal("55")

    var p = PositionState.empty("ACC-1001", "XYZ", now)
    p = effective.foldLeft(p)(AverageCostMethod.applyTrade)
    p.quantity shouldBe 100
    p.averageCost shouldBe BigDecimal("55.000000")
  }
}
