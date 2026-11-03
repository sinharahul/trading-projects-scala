package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ParserSpec extends AnyFlatSpec with Matchers {

  private val header = "statementId,custodian,account,instrument,quantity," +
    "cashAmount,currency,valueDate,reference,entryType"

  "StatementParser" should "parse a clean settlement line" in {
    val text = header + "\n" +
      "STMT-1,BNY,ACC-1001,AAPL,100,-17558.78,USD,2026-11-02,CUST-1,SETTLEMENT"
    val result = StatementParser.parseText(text)
    result.errors shouldBe empty
    result.entries should have size 1
    result.entries.head.instrument shouldBe "AAPL"
    result.entries.head.valueDate shouldBe LocalDate.parse("2026-11-02")
  }

  it should "reject a same-sign DvP line instead of matching it later" in {
    val text = header + "\n" +
      "STMT-1,BNY,ACC-1001,AAPL,100,17558.78,USD,2026-11-02,CUST-1,SETTLEMENT"
    val result = StatementParser.parseText(text)
    result.entries shouldBe empty
    result.errors should have size 1
    result.errors.head.lineNumber shouldBe 2
  }

  it should "collect one bad line's error without losing the good lines" in {
    val text = header + "\n" +
      "STMT-1,BNY,ACC-1001,AAPL,100,-17558.78,USD,2026-11-02,CUST-1,SETTLEMENT\n" +
      "STMT-2,BNY,ACC-1001,MSFT,not-a-number,20989.50,USD,2026-10-30,CUST-2,SETTLEMENT\n" +
      "STMT-3,BNY,ACC-1001,MSFT,-50,20989.50,USD,2026-10-30,CUST-3,SETTLEMENT"
    val result = StatementParser.parseText(text)
    result.entries should have size 2
    result.errors should have size 1
  }

  it should "skip blank lines" in {
    val text = header + "\n\n" +
      "STMT-1,BNY,ACC-1001,AAPL,100,-17558.78,USD,2026-11-02,CUST-1,SETTLEMENT\n"
    StatementParser.parseText(text).entries should have size 1
  }
}
