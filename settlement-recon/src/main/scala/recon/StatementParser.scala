package recon

import java.time.LocalDate
import scala.io.Source
import scala.util.{Failure, Success, Try}

/** One parse problem, with the line number so operations can find it. */
case class ParseError(lineNumber: Int, line: String, message: String)
  extends Exception(s"Line $lineNumber: $message (line was: $line)")

/** Result of parsing a whole file: good entries plus per-line errors.
  * The file is never rejected wholesale for one bad line. */
case class ParseResult(entries: Seq[StatementEntry], errors: Seq[ParseError])

object StatementParser {

  private val expectedHeader =
    "statementId,custodian,account,instrument,quantity,cashAmount,currency,valueDate,reference,entryType"

  def parseFile(path: String): ParseResult = {
    val lines = Source.fromFile(path).getLines().toList
    parseLines(lines)
  }

  def parseText(text: String): ParseResult =
    parseLines(text.split("\n").toList)

  def parseLines(lines: List[String]): ParseResult = {
    lines match {
      case Nil => ParseResult(Nil, Nil)
      case header :: rest if header.trim == expectedHeader =>
        collectResults(rest.zipWithIndex.map { case (line, idx) =>
          if (line.trim.isEmpty) None else Some(parseLine(line, idx + 2))
        }.flatten)
      case header :: rest =>
        parseWithHeader(header, rest)
    }
  }

  private def collectResults(
    parsed: Seq[Either[ParseError, StatementEntry]]
  ): ParseResult = ParseResult(
    parsed.collect { case Right(e) => e },
    parsed.collect { case Left(err) => err }
  )

  /** Parse when the header row differs from the expected order: map columns
    * by name instead of position. Kept simple on purpose: if a required
    * column is absent, every line reports that. */
  private def parseWithHeader(header: String, rest: List[String]): ParseResult = {
    val cols = header.split(",", -1).map(_.trim).toList
    val required = List("statementId", "custodian", "account", "instrument",
      "quantity", "cashAmount", "currency", "valueDate", "reference", "entryType")
    val missing = required.filterNot(cols.contains)
    if (missing.nonEmpty) {
      return ParseResult(Nil, Seq(ParseError(1, header,
        s"Missing columns: ${missing.mkString(", ")}")))
    }
    val idx = required.map(cols.indexOf).toArray
    val parsed = rest.zipWithIndex.flatMap { case (line, i) =>
      if (line.trim.isEmpty) None
      else {
        val parts = line.split(",", -1).map(_.trim)
        def col(name: String): String = parts(idx(required.indexOf(name)))
        Some(parseFields(col, line, i + 2))
      }
    }
    ParseResult(
      parsed.collect { case Right(e) => e },
      parsed.collect { case Left(err) => err }
    )
  }

  private def parseLine(line: String, lineNumber: Int): Either[ParseError, StatementEntry] = {
    val parts = line.split(",", -1).map(_.trim)
    if (parts.length != 10)
      Left(ParseError(lineNumber, line, s"Expected 10 columns, got ${parts.length}"))
    else
      parseFields(name => parts(List("statementId", "custodian", "account", "instrument",
        "quantity", "cashAmount", "currency", "valueDate", "reference", "entryType").indexOf(name)),
        line, lineNumber)
  }

  private def parseFields(
    col: String => String,
    line: String,
    lineNumber: Int
  ): Either[ParseError, StatementEntry] = {
    def fail(msg: String): Left[ParseError, Nothing] =
      Left(ParseError(lineNumber, line, msg))

    val quantity = Try(BigDecimal(col("quantity"))) match {
      case Success(v) => v
      case Failure(_) => return fail(s"Bad quantity '${col("quantity")}'")
    }
    val cash = Try(BigDecimal(col("cashAmount"))) match {
      case Success(v) => v
      case Failure(_) => return fail(s"Bad cashAmount '${col("cashAmount")}'")
    }
    val valueDate = Try(LocalDate.parse(col("valueDate"))) match {
      case Success(v) => v
      case Failure(_) => return fail(s"Bad valueDate '${col("valueDate")}'")
    }
    val entryType = col("entryType")
    if (entryType.isEmpty) return fail("Empty entryType")
    if (col("instrument").isEmpty) return fail("Empty instrument")
    if (col("currency").isEmpty) return fail("Empty currency")

    // DvP sanity check: for SETTLEMENT entries, securities and cash must
    // move in opposite directions. Same-sign lines are corrupt data.
    if (entryType == "SETTLEMENT" && quantity.signum != 0 && cash.signum != 0 &&
        quantity.signum == cash.signum) {
      return fail("SETTLEMENT line has same-sign quantity and cash (expected DvP)")
    }

    Right(StatementEntry(
      statementId = col("statementId"),
      custodian = col("custodian"),
      account = col("account"),
      instrument = col("instrument"),
      quantity = quantity,
      cashAmount = cash,
      currency = col("currency"),
      valueDate = valueDate,
      reference = col("reference"),
      entryType = entryType
    ))
  }
}
