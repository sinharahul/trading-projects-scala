package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import java.sql.Timestamp

class PositionPropertySpec extends AnyFlatSpec with Matchers
    with ScalaCheckPropertyChecks {
  private val now = Timestamp.valueOf("2026-10-30 09:30:00")

  private val tradeGen: Gen[Trade] = for {
    side <- Gen.oneOf(Buy, Sell)
    qty <- Gen.choose(1L, 500L)
    price <- Gen.choose(10.0, 500.0)
      .map(p => BigDecimal(p).setScale(2, BigDecimal.RoundingMode.HALF_UP))
  } yield Trade(java.util.UUID.randomUUID().toString,
    "ACC-1001", "XYZ", side, qty, price, now, "PROPERTY")

  private def foldAll(method: CostBasisMethod, trades: List[Trade]): PositionState =
    trades.foldLeft(PositionState.empty("ACC-1001", "XYZ", now)) {
      (state, trade) => method.applyTrade(state, trade)
    }

  "Position state" should "conserve quantity for any trade sequence" in {
    forAll(Gen.listOf(tradeGen)) { trades =>
      val finalState = foldAll(AverageCostMethod, trades)
      val expectedQty = trades.map(t => Side.sign(t.side) * t.quantity).sum
      finalState.quantity shouldBe expectedQty
    }
  }

  it should "agree between methods when a sequence ends flat" in {
    forAll(Gen.listOf(tradeGen)) { trades =>
      val fifo = foldAll(FifoMethod, trades)
      val avg = foldAll(AverageCostMethod, trades)
      whenever(fifo.isFlat && avg.isFlat) {
        fifo.realizedPnl shouldBe avg.realizedPnl
      }
    }
  }
}
