package recon

import java.time.LocalDate

object Main extends App {
  val calendar = SettlementCalendar.fromResource("us-holidays-2026.txt")
  val ssiRepo = SsiRepository.fromResource("ssi.csv")
  val generator = new InstructionGenerator(calendar, ssiRepo, FeeSchedule.default)

  val trades = Seq(
    Trade("T001", "AAPL", Buy, BigDecimal(100), BigDecimal("175.50"), "USD",
      LocalDate.parse("2026-10-30"), "BROKER-A", "ACC-1001", "BNY"),
    Trade("T002", "MSFT", Sell, BigDecimal(50), BigDecimal("420.00"), "USD",
      LocalDate.parse("2026-10-29"), "BROKER-B", "ACC-1001", "BNY"),
    Trade("T003", "US91282CJL67", Buy, BigDecimal(1000000), BigDecimal("99.25"), "USD",
      LocalDate.parse("2026-11-25"), "BROKER-A", "ACC-1002", "BNY")
  )

  generator.generateAll(trades).foreach { inst =>
    println(s"${inst.instructionId} ${inst.instrument} ${inst.side} " +
      s"qty=${inst.quantity} cash=${inst.cashAmount} ${inst.currency} " +
      s"settle=${inst.settlementDate} acct=${inst.account} ssi=${inst.ssiId}")
  }

  val parsed = StatementParser.parseFile("data/custodian-bny-2026-11-02.csv")
  println(s"Parsed ${parsed.entries.size} entries, ${parsed.errors.size} errors")
  parsed.errors.foreach(e => println(s"  ERROR: ${e.getMessage}"))
  parsed.entries.foreach { e =>
    println(s"  ${e.statementId} ${e.instrument} qty=${e.quantity} " +
      s"cash=${e.cashAmount} ${e.currency} date=${e.valueDate} acct=${e.account} ${e.entryType}")
  }
  val extraTrades = Seq(
    Trade("T004", "TSLA", Buy, BigDecimal(10), BigDecimal("250.00"), "USD",
      LocalDate.parse("2026-10-30"), "BROKER-A", "ACC-1001", "BNY"),
    Trade("T005", "NVDA", Buy, BigDecimal(20), BigDecimal("180.00"), "USD",
      LocalDate.parse("2026-10-30"), "BROKER-A", "ACC-1001", "BNY")
  )
  val allInstructions = generator.generateAll(trades ++ extraTrades)
  val matcher = new Matcher(MatchTolerances.default(calendar))
  val results = matcher.matchAll(allInstructions, parsed.entries)
  results.foreach { r =>
    val instId = r.instruction.map(_.instructionId).getOrElse("-")
    val stmtId = r.statement.map(_.statementId).getOrElse("-")
    println(s"${r.kind} inst=$instId stmt=$stmtId ${r.notes}")
  }
  println(s"Exact: ${results.count(_.kind == ExactMatch)}, " +
    s"Fuzzy: ${results.count(_.kind == FuzzyMatch)}, " +
    s"Unmatched instructions: ${results.count(_.kind == UnmatchedInstruction)}, " +
    s"Unmatched statements: ${results.count(_.kind == UnmatchedStatement)}")
  val classifier = new BreakClassifier(asOfDate = LocalDate.parse("2026-11-03"))
  val breaks = classifier.classify(results, allInstructions)
  println(s"\nBreaks: ${breaks.size}")
  breaks.foreach { b =>
    println(s"  ${b.breakId} ${b.breakType} ${b.severity} " +
      s"trade=${b.tradeId.getOrElse("-")} stmt=${b.statementId.getOrElse("-")} :: ${b.description}")
  }
  val knownFees = Seq(
    KnownFee("BNY", "USD", BigDecimal("25.00"), "BNY settlement fee")
  )
  val resolver = new ResolutionEngine(knownFees, LocalDate.parse("2026-11-03"))
  val (resolutions, queue) = resolver.resolve(breaks)
  println(s"\nAuto-resolved: ${resolutions.size}")
  resolutions.foreach(r => println(s"  ${r.breakId} ${r.outcome} :: ${r.reason}"))
  println(s"Manual queue: ${queue.size}")
  queue.foreach { item =>
    println(s"  ${item.break.breakId} ${item.break.breakType} -> " +
      s"${item.assignedTeam}: ${item.suggestedAction}")
  }
  val openBreaks = queue.map(_.break)
  val aging = AgingReport.build(openBreaks, LocalDate.parse("2026-11-03"))
  println("\n" + AgingReport.render(aging))
  val staleAging = AgingReport.build(openBreaks, LocalDate.parse("2026-12-10"))
  println("\n" + AgingReport.render(staleAging))
  val cashReport = CashReconciliation.reconcile(allInstructions, parsed.entries)
  println("\n" + CashReconciliation.render(cashReport))
  val dashboard = Dashboard.build(breaks, resolutions, cashReport, LocalDate.parse("2026-11-03"))
  println("\n" + Dashboard.render(dashboard))
}
