package com.backoffice.capture

import com.backoffice.capture.model._
import com.backoffice.capture.parsing.FixParser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FixParserSpec extends AnyFlatSpec with Matchers {

  private def msg(overrides: Map[String, String] = Map.empty): String = {
    val base = Map(
      "8" -> "FIX.4.2", "35" -> "8", "49" -> "EXCHANGE_X", "56" -> "BROKER_A",
      "34" -> "1042", "52" -> "20261030-14:32:11.456", "11" -> "ORD-88412",
      "17" -> "EXEC-99173", "150" -> "F", "39" -> "2", "55" -> "AAPL",
      "54" -> "1", "38" -> "10000", "32" -> "2500", "31" -> "227.45",
      "60" -> "20261030-14:32:10.120"
    ) ++ overrides
    // Checksum intentionally omitted here: the test helper recomputes it.
    val body = base.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("|") + "|"
    val sum = body.getBytes("US-ASCII").map(b => b & 0xff).sum % 256
    body + s"10=${f"$sum%03d"}|"
  }

  "FixParser" should "parse a valid execution report" in {
    val result = FixParser.parse(msg())
    result.isRight shouldBe true
    result.map(_.symbol) shouldBe Right("AAPL")
    result.map(_.lastQty) shouldBe Right(2500L)
  }

  it should "reject a message missing the symbol" in {
    val broken = msg().replace("55=AAPL|", "")
    FixParser.parse(broken).isLeft shouldBe true
  }

  it should "reject a non-numeric price" in {
    val broken = msg(Map("31" -> "ABC"))
    FixParser.parse(broken).isLeft shouldBe true
  }

  it should "reject an unknown side code" in {
    val broken = msg(Map("54" -> "9"))
    FixParser.parse(broken).isLeft shouldBe true
  }

  it should "reject a message with a bad checksum" in {
    val corrupted = msg().replaceAll("\\|10=\\d{3}\\|", "|10=000|")
    FixParser.parse(corrupted).isLeft shouldBe true
  }
}
