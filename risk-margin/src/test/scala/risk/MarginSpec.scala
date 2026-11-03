package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.Fixtures
import risk.exposure.ExposureCalculator
import risk.margin.{MarginAccount, MarginCalculator}

class MarginSpec extends AnyFlatSpec with Matchers {
  private val (exp, _) = ExposureCalculator.compute(
    Fixtures.bookA1, Fixtures.prices, Fixtures.infos)

  "MarginCalculator" should "match hand-computed Reg T figures" in {
    MarginCalculator.regTInitial(exp) shouldBe BigDecimal(115500)
    MarginCalculator.maintenance(exp) shouldBe BigDecimal(47850)
  }

  it should "match the hand-computed portfolio margin" in {
    MarginCalculator.portfolioMargin(exp, Fixtures.infos) shouldBe
      BigDecimal(41750)
  }

  it should "show no call for the unleveraged account" in {
    val r = MarginCalculator.compute(
      MarginAccount("A1", BigDecimal(60000), BigDecimal(0)),
      exp, Fixtures.infos)
    r.equity shouldBe BigDecimal(203000)
    r.marginCall shouldBe BigDecimal(0)
  }

  it should "issue a 14,850 call for the leveraged account" in {
    val r = MarginCalculator.compute(
      MarginAccount("A2", BigDecimal(10000), BigDecimal(120000)),
      exp, Fixtures.infos)
    r.equity shouldBe BigDecimal(33000)
    r.marginCall shouldBe BigDecimal(14850)
  }

  it should "never report a negative margin call" in {
    val r = MarginCalculator.compute(
      MarginAccount("A1", BigDecimal(60000), BigDecimal(0)),
      exp, Fixtures.infos)
    r.marginCall should be >= BigDecimal(0)
  }
}
