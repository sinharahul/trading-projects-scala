package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.Fixtures
import risk.exposure.ExposureCalculator

class ExposureSpec extends AnyFlatSpec with Matchers {
  "ExposureCalculator" should "match the hand-computed fixture book" in {
    val (exp, missing) = ExposureCalculator.compute(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos)
    missing shouldBe empty
    exp.longExposure shouldBe BigDecimal(165000)
    exp.shortExposure shouldBe BigDecimal(22000)
    exp.grossExposure shouldBe BigDecimal(187000)
    exp.netExposure shouldBe BigDecimal(143000)
    // Beta-adjusted: 120000*1.2 + 45000*0.8 + (-22000)*0.5
    //              = 144000 + 36000 - 11000 = 169000
    exp.betaAdjustedExposure shouldBe BigDecimal(169000)
  }

  it should "report missing prices instead of valuing at zero silently" in {
    val (_, missing) = ExposureCalculator.compute(
      Fixtures.bookA1, Map("ACME" -> BigDecimal(120)), Fixtures.infos)
    missing should contain("GLOB")
    missing should contain("STAB")
  }

  it should "show gross exposure hiding inside a market-neutral book" in {
    val neutral = List(
      risk.domain.Position("A9", "D", "ACME", BigDecimal(1000), BigDecimal(100)),
      risk.domain.Position("A9", "D", "GLOB", BigDecimal(-1333), BigDecimal(90))
    )
    val prices = Map("ACME" -> BigDecimal(120), "GLOB" -> BigDecimal(90))
    val (exp, _) = ExposureCalculator.compute(neutral, prices, Fixtures.infos)
    exp.netExposure shouldBe BigDecimal(120000 - 119970) // near zero
    exp.grossExposure shouldBe BigDecimal(120000 + 119970) // but huge
  }
}
