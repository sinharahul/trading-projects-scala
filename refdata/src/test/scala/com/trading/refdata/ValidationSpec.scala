package com.trading.refdata

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidationSpec extends AnyFlatSpec with Matchers {
  "CUSIP validation" should "accept real CUSIPs" in {
    Validation.isValidCusip("037833100") shouldBe true // Apple
    Validation.isValidCusip("594918104") shouldBe true // Microsoft
  }
  it should "reject a CUSIP with a wrong check digit" in {
    Validation.isValidCusip("037833101") shouldBe false
    Validation.isValidCusip("03783310") shouldBe false // too short
  }
  "ISIN validation" should "accept real ISINs" in {
    Validation.isValidIsin("US0378331005") shouldBe true // Apple
    Validation.isValidIsin("US5949181045") shouldBe true // Microsoft
  }
  it should "reject a wrong check digit" in {
    Validation.isValidIsin("US0378331006") shouldBe false
  }
  "SEDOL validation" should "accept a real SEDOL" in {
    Validation.isValidSedol("2046251") shouldBe true // Apple (SEDOL)
  }
}
