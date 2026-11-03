package recon

import java.time.LocalDate

/** Converts an instruction into the custodian's sign conventions so that
  * a direct comparison is meaningful. Documented once, used everywhere. */
object StatementConventions {

  /** Statement quantity sign: buys positive (shares in), sells negative. */
  def statementQuantity(inst: SettlementInstruction): BigDecimal =
    inst.side match {
      case Buy  => inst.quantity
      case Sell => -inst.quantity
    }

  /** Statement cash sign: buys negative (cash out), sells positive (cash in).
    * This is exactly the negation of the instruction convention. */
  def statementCash(inst: SettlementInstruction): BigDecimal =
    -inst.cashAmount
}

/** Matching tolerances. All values are explicit and tunable. */
case class MatchTolerances(
  cashAbsolute: BigDecimal,       // e.g. 0.05
  cashRelative: BigDecimal,       // e.g. 0.0001 = 0.01%
  dateBusinessDays: Int,          // e.g. 2
  businessCalendar: SettlementCalendar
) {
  def cashWithinTolerance(expected: BigDecimal, actual: BigDecimal): Boolean = {
    val diff = (expected - actual).abs
    val relativeLimit = (expected.abs * cashRelative).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    diff <= cashAbsolute.max(relativeLimit)
  }

  def dateWithinTolerance(expected: java.time.LocalDate, actual: java.time.LocalDate): Boolean = {
    // Count business days between the two dates, ignoring direction.
    val (earlier, later) =
      if (expected.isBefore(actual)) (expected, actual) else (actual, expected)
    var cursor = earlier
    var businessDays = 0
    while (cursor.isBefore(later)) {
      cursor = cursor.plusDays(1)
      if (businessCalendar.isBusinessDay(cursor)) businessDays += 1
    }
    businessDays <= dateBusinessDays
  }
}

object MatchTolerances {
  def default(calendar: SettlementCalendar): MatchTolerances = MatchTolerances(
    cashAbsolute = BigDecimal("0.05"),
    cashRelative = BigDecimal("0.0001"),
    dateBusinessDays = 2,
    businessCalendar = calendar
  )
}


sealed trait MatchKind
case object ExactMatch extends MatchKind
case object FuzzyMatch extends MatchKind
case object UnmatchedInstruction extends MatchKind
case object UnmatchedStatement extends MatchKind

case class MatchResult(
  instruction: Option[SettlementInstruction],
  statement: Option[StatementEntry],
  kind: MatchKind,
  cashDifference: Option[BigDecimal],
  dateDifferenceDays: Option[Int],
  notes: String
)

/** Two-pass matching engine: exact pass, then fuzzy pass on leftovers. */
class Matcher(tolerances: MatchTolerances) {

  def matchAll(
    instructions: Seq[SettlementInstruction],
    statements: Seq[StatementEntry]
  ): Seq[MatchResult] = {
    // Only SETTLEMENT entries participate in trade matching. Fees, taxes,
    // and cash-only entries go to cash reconciliation (Step 7).
    val settlementStatements = statements.filter(_.entryType == "SETTLEMENT")

    // ---- Pass 1: exact matches ----
    val (exactPairs, remainingInst1, remainingStmt1) =
      exactPass(instructions, settlementStatements)

    // ---- Pass 2: fuzzy matches on what is left ----
    val (fuzzyPairs, remainingInst2, remainingStmt2) =
      fuzzyPass(remainingInst1, remainingStmt1)

    val exactResults = exactPairs.map { case (inst, stmt) =>
      MatchResult(Some(inst), Some(stmt), ExactMatch, Some(BigDecimal(0)), Some(0),
        "All key fields agree")
    }
    val fuzzyResults = fuzzyPairs.map { case (inst, stmt, cashDiff, dateDiff, note) =>
      MatchResult(Some(inst), Some(stmt), FuzzyMatch, Some(cashDiff), Some(dateDiff), note)
    }
    val unmatchedInst = remainingInst2.map { inst =>
      MatchResult(Some(inst), None, UnmatchedInstruction, None, None,
        "No statement entry matched this instruction")
    }
    val unmatchedStmt = remainingStmt2.map { stmt =>
      MatchResult(None, Some(stmt), UnmatchedStatement, None, None,
        "No instruction matched this statement entry")
    }
    exactResults ++ fuzzyResults ++ unmatchedInst ++ unmatchedStmt
  }

  private def isExact(
    inst: SettlementInstruction,
    stmt: StatementEntry
  ): Boolean = {
    inst.instrument == stmt.instrument &&
    StatementConventions.statementQuantity(inst) == stmt.quantity &&
    StatementConventions.statementCash(inst) == stmt.cashAmount &&
    inst.currency == stmt.currency &&
    inst.settlementDate == stmt.valueDate &&
    inst.account == stmt.account &&
    inst.custodian == stmt.custodian
  }

  private def exactPass(
    instructions: Seq[SettlementInstruction],
    statements: Seq[StatementEntry]
  ): (Seq[(SettlementInstruction, StatementEntry)],
      Seq[SettlementInstruction], Seq[StatementEntry]) = {
    var remainingStmts = statements.toList
    val pairs = scala.collection.mutable.ListBuffer.empty[(SettlementInstruction, StatementEntry)]
    val remainingInsts = instructions.filter { inst =>
      val idx = remainingStmts.indexWhere(stmt => isExact(inst, stmt))
      if (idx >= 0) {
        pairs += ((inst, remainingStmts(idx)))
        remainingStmts = remainingStmts.patch(idx, Nil, 1)
        false // matched: remove from remaining instructions
      } else true
    }
    (pairs.toSeq, remainingInsts, remainingStmts)
  }

  /** Fuzzy candidate: same instrument, quantity, currency, custodian, and
    * account; cash within tolerance and date within tolerance. Scored so
    * that the best candidate wins when several compete. */
  private def fuzzyScore(
    inst: SettlementInstruction,
    stmt: StatementEntry
  ): Option[(BigDecimal, Int, String)] = {
    if (inst.instrument != stmt.instrument) return None
    if (StatementConventions.statementQuantity(inst) != stmt.quantity) return None
    if (inst.currency != stmt.currency) return None
    if (inst.custodian != stmt.custodian) return None
    if (inst.account != stmt.account) return None

    val expectedCash = StatementConventions.statementCash(inst)
    if (!tolerances.cashWithinTolerance(expectedCash, stmt.cashAmount)) return None
    if (!tolerances.dateWithinTolerance(inst.settlementDate, stmt.valueDate)) return None

    val cashDiff = (expectedCash - stmt.cashAmount).abs
    val dateDiff = dateBusinessDayDistance(inst.settlementDate, stmt.valueDate)
    val notes = List(
      if (cashDiff != 0) Some(s"cash differs by $cashDiff") else None,
      if (dateDiff != 0) Some(s"date differs by $dateDiff business days") else None
    ).flatten.mkString("; ")
    Some((cashDiff, dateDiff, if (notes.isEmpty) "Within tolerance on all fields" else notes))
  }

  private def dateBusinessDayDistance(
    a: java.time.LocalDate, b: java.time.LocalDate
  ): Int = {
    val (earlier, later) = if (a.isBefore(b)) (a, b) else (b, a)
    var cursor = earlier
    var days = 0
    while (cursor.isBefore(later)) {
      cursor = cursor.plusDays(1)
      if (tolerances.businessCalendar.isBusinessDay(cursor)) days += 1
    }
    days
  }

  private def fuzzyPass(
    instructions: Seq[SettlementInstruction],
    statements: Seq[StatementEntry]
  ): (Seq[(SettlementInstruction, StatementEntry, BigDecimal, Int, String)],
      Seq[SettlementInstruction], Seq[StatementEntry]) = {
    var remainingStmts = statements.toList
    val pairs = scala.collection.mutable.ListBuffer.empty[
      (SettlementInstruction, StatementEntry, BigDecimal, Int, String)]
    val remainingInsts = instructions.filter { inst =>
      // Score every remaining candidate; take the best (smallest cash
      // difference, then smallest date difference).
      val candidates = remainingStmts.flatMap { stmt =>
        fuzzyScore(inst, stmt).map { case (cashDiff, dateDiff, note) =>
          (stmt, cashDiff, dateDiff, note)
        }
      }
      if (candidates.isEmpty) true
      else {
        val (stmt, cashDiff, dateDiff, note) =
          candidates.sortBy(c => (c._2, c._3)).head
        pairs += ((inst, stmt, cashDiff, dateDiff, note))
        remainingStmts = remainingStmts.filterNot(_ == stmt)
        false
      }
    }
    (pairs.toSeq, remainingInsts, remainingStmts)
  }
}
