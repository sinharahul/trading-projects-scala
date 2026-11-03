package com.trading.refdata

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.LocalDate

class ReferenceDataSpec extends AnyFlatSpec with Matchers {

  private def record(vendor: String, id: String, name: String,
                     cusip: Option[String], ticker: Option[String],
                     sector: String, lei: Option[String]): VendorRecord =
    VendorRecord(
      vendor = vendor, vendorSecurityId = id, name = name,
      cusip = cusip, isin = None, sedol = None, figi = None,
      ticker = ticker, mic = Some("XNAS"), assetClass = "EQUITY",
      securityType = "COMMON_STOCK", currency = "USD",
      issuerName = name, issuerLei = lei, country = "US",
      sector = sector, status = "ACTIVE",
      asOfDate = LocalDate.of(2026, 10, 30), rawHash = "test"
    )

  "Matching" should "link records sharing a valid CUSIP" in {
    val a = record("VENDOR_A", "A-1", "Apple Inc.", Some("037833100"), Some("AAPL"), "Technology", None)
    val b = record("VENDOR_B", "B-1", "Apple Inc", Some("037833100"), Some("AAPL"), "Information Technology", None)
    val outcome = Matching.link(b, Seq(a), _ => Some("GOLDEN-APPLE"), (_, _) => Nil)
    outcome shouldBe MatchOutcome.Linked("GOLDEN-APPLE", "exact-Cusip", 1.0)
  }

  it should "quarantine the identifier-reuse trap" in {
    val apple = record("VENDOR_A", "A-1", "Apple Inc.", Some("037833100"), Some("AAPL"), "Technology", None)
    val trap = record("VENDOR_B", "B-9", "Old Ticker Corporation", Some("037833100"), Some("OTC"), "Technology", None)
    val outcome = Matching.link(trap, Seq(apple), _ => Some("GOLDEN-APPLE"), (_, _) => Nil)
    outcome.isInstanceOf[MatchOutcome.NeedsReview] shouldBe true
  }

  it should "match on ticker plus similar name when no identifier is shared" in {
    val a = record("VENDOR_A", "A-2", "Microsoft Corporation", None, Some("MSFT"), "Technology", None)
    val b = record("VENDOR_B", "B-2", "Microsoft Corp.", None, Some("MSFT"), "Technology", None)
    val outcome = Matching.link(b, Seq(a), _ => Some("GOLDEN-MSFT"), (_, _) => Nil)
    outcome match {
      case MatchOutcome.Linked(key, _, _) => key shouldBe "GOLDEN-MSFT"
      case other => fail(s"Expected a link, got $other")
    }
  }

  "Survivorship" should "prefer Vendor B for LEI" in {
    val a = record("VENDOR_A", "A-4", "Meta Platforms Inc", Some("30303M102"), Some("META"), "Technology", Some("549300MFX2Z3VXH0M1"))
    val b = record("VENDOR_B", "B-4", "Meta Platforms, Inc.", Some("30303M102"), Some("META"), "Communication Services", Some("5493002P3G3G6G0G0G0G"))
    val golden = Survivorship.survive("GOLDEN-META", Seq(a, b))
    golden.issuerLei.value shouldBe Some("5493002P3G3G6G0G0G0G")
    golden.issuerLei.winningSource shouldBe "VENDOR_B"
  }

  it should "resolve sector by vote with the Vendor B tie-break" in {
    val a = record("VENDOR_A", "A-3", "Alphabet Inc. Class A", Some("02079K305"), Some("GOOGL"), "Technology", None)
    val b = record("VENDOR_B", "B-3", "Alphabet Inc. - Class A", Some("02079K305"), Some("GOOGL"), "Communication Services", None)
    val golden = Survivorship.survive("GOLDEN-GOOGL", Seq(a, b))
    golden.sector.value shouldBe "Communication Services"
  }

  it should "never let an invalid identifier win" in {
    val a = record("VENDOR_A", "A-9", "Fake Corp", Some("123456789"), Some("FAKE"), "Technology", None)
    val golden = Survivorship.survive("GOLDEN-FAKE", Seq(a))
    golden.cusip.value shouldBe None
  }

  "SCD change detection" should "ignore immaterial differences" in {
    val oldRow = GoldenRow(
      "GOLDEN-APPLE", "Apple Inc.", Some("037833100"), Some("US0378331005"),
      Some("2046251"), Some("BBG000B9XRY4"), Some("AAPL"), Some("XNAS"),
      "EQUITY", "COMMON_STOCK", "USD", "Apple Inc.",
      Some("HWUPKR0MPOU8FGXBT09"), "US", "Information Technology", "ACTIVE",
      LocalDate.of(2026, 10, 30), None, true, "INITIAL_LOAD", "name=VENDOR_A"
    )
    val g = Survivorship.survive("GOLDEN-APPLE", Seq(
      record("VENDOR_A", "A-1", "Apple Inc.", Some("037833100"), Some("AAPL"), "Information Technology", Some("HWUPKR0MPOU8FGXBT09"))
    ))
    // The survived record differs from oldRow only in fields the
    // change detector compares after normalisation; sector matches,
    // so the changed list must not contain sector.
    Scd2Writer.changedFields(oldRow, g) should not contain "sector"
  }

  "Data quality" should "quarantine a security with no valid identifier" in {
    val fake = Survivorship.survive("GOLDEN-FAKE", Seq(
      record("VENDOR_A", "A-9", "Fake Corp", Some("123456789"), Some("FAKE"), "Technology", None)
    ))
    val findings = DataQuality.checkRow(fake, Seq(fake))
    findings.exists(f =>
      f.checkName == "completeness.identifier" &&
        f.severity == Severity.Blocking) shouldBe true
  }

  it should "flag a duplicate CUSIP across two securities" in {
    val one = Survivorship.survive("GOLDEN-1", Seq(
      record("VENDOR_A", "A-1", "Apple Inc.", Some("037833100"), Some("AAPL"), "Technology", None)))
    val two = Survivorship.survive("GOLDEN-2", Seq(
      record("VENDOR_B", "B-1", "Apple Inc", Some("037833100"), Some("AAPL"), "Technology", None)))
    val findings = DataQuality.checkRow(two, Seq(one, two))
    findings.exists(_.checkName == "uniqueness.identifier") shouldBe true
  }
}
