package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.{Fixtures, Price}
import risk.returns.PriceHistoryLoader
import risk.varx.HistoricalVaR

class VaRSpec extends AnyFlatSpec with Matchers {
  private val history =
    PriceHistoryLoader.load("src/main/resources/data/prices.csv")
  private val currentValue = BigDecimal(143000)

  "HistoricalVaR" should "reproduce the hand-computed 95% VaR" in {
    val r = HistoricalVaR.compute(
      Fixtures.bookA1, history, currentValue, 0.95)
    r.observationCount shouldBe 20
    // Hand computation: threshold return -0.6980%, dollars 998
    r.varDollars.setScale(0, BigDecimal.RoundingMode.HALF_UP) shouldBe
      BigDecimal(998)
    r.worstDate.toString shouldBe "2026-09-03"
  }

  it should "reproduce the hand-computed 99% VaR and show its fragility" in {
    val r = HistoricalVaR.compute(
      Fixtures.bookA1, history, currentValue, 0.99)
    r.varDollars.setScale(0, BigDecimal.RoundingMode.HALF_UP) shouldBe
      BigDecimal(1040)
    // With 20 observations, 99% VaR interpolates between the same two
    // worst days as 95% VaR. The test pins the number; the commentary
    // in the report must warn that it carries almost no information.
    (r.varDollars - BigDecimal(998)) should be < BigDecimal(100)
  }

  it should "fail loudly on a missing price instead of inventing a return" in {
    val broken = history.updated("ACME", history("ACME").drop(5))
    assertThrows[IllegalArgumentException] {
      HistoricalVaR.compute(Fixtures.bookA1, broken, currentValue, 0.95)
    }
  }

  it should "agree closely between simple and log returns at 1 day" in {
    val series = HistoricalVaR.portfolioValueSeries(Fixtures.bookA1, history)
    val simple = HistoricalVaR.simpleReturns(series).map(_._2).min
    val log = HistoricalVaR.logReturns(series).map(_._2).min
    math.abs(simple - log) should be < 0.001
  }
}
