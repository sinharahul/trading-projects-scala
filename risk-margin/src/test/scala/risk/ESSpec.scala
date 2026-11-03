package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.Fixtures
import risk.returns.PriceHistoryLoader
import risk.varx.ExpectedShortfall

class ESSpec extends AnyFlatSpec with Matchers {
  private val history =
    PriceHistoryLoader.load("src/main/resources/data/prices.csv")

  "ExpectedShortfall" should "exceed VaR on the fixture book" in {
    val es = ExpectedShortfall.compute(
      Fixtures.bookA1, history, BigDecimal(143000), 0.95)
    es.esDollars.setScale(0, BigDecimal.RoundingMode.HALF_UP) shouldBe
      BigDecimal(1051)
    // ES must be >= VaR by construction: it averages losses beyond VaR.
    es.esDollars should be >= BigDecimal(998)
    es.tailCount shouldBe 1 // the fragility warning, in data form
  }

  it should "be at least as large as VaR at 99% as well" in {
    val es = ExpectedShortfall.compute(
      Fixtures.bookA1, history, BigDecimal(143000), 0.99)
    es.esDollars should be >= BigDecimal(1040)
  }
}
