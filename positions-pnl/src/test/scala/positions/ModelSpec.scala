package positions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class ModelSpec extends AnyFlatSpec with Matchers {
  "Money.cost" should "multiply quantity by price and round to cents" in {
    Money.cost(100, BigDecimal("50.005")) shouldBe BigDecimal("5000.50")
  }

  "PositionState.empty" should "start flat with zero cost" in {
    val now = new Timestamp(System.currentTimeMillis())
    val p = PositionState.empty("ACC-1001", "AAPL", now)
    p.isFlat shouldBe true
    p.averageCost shouldBe BigDecimal(0)
  }

  "Side.sign" should "give buys a positive sign and sells a negative sign" in {
    Side.sign(Buy) shouldBe 1L
    Side.sign(Sell) shouldBe -1L
  }
}
