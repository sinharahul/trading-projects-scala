package recon

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SsiSpec extends AnyFlatSpec with Matchers {

  private val repo = new SsiRepository(Seq(
    Ssi("SSI-BNY-USD-EQ", "BNY", "USD", "EQUITY", "ACC-1001"),
    Ssi("SSI-BNY-USD-FI", "BNY", "USD", "FIXED_INCOME", "ACC-1002")
  ))

  "SsiRepository" should "resolve the exact market match" in {
    repo.resolve("BNY", "USD", "EQUITY").account shouldBe "ACC-1001"
    repo.resolve("BNY", "USD", "FIXED_INCOME").account shouldBe "ACC-1002"
  }

  it should "fall back to custodian+currency when the market is unknown" in {
    // An unknown market label still resolves, via the fallback, rather
    // than failing — the reconciliation engine will catch a wrong account
    // later, which is the safer failure mode than no instruction at all.
    repo.resolve("BNY", "USD", "DERIVATIVES").account shouldBe "ACC-1001"
  }

  it should "fail loudly when no SSI matches at all" in {
    an[IllegalArgumentException] should be thrownBy {
      repo.resolve("CITI", "JPY", "EQUITY")
    }
  }
}
