package com.trading.reporting

object ReportingPipelineDriver {
  def main(args: Array[String]): Unit = {
    val reportingDate = Fixtures.reportingDate

    // Step 2: link.
    val linked = OrderLinker.link(
      List(Fixtures.trade1, Fixtures.trade2, Fixtures.trade3),
      Fixtures.eventsFor("ORD-1", "AAPL") ++
        Fixtures.eventsFor("ORD-2", "AAPL261120C00175000") ++
        Fixtures.eventsFor("ORD-3", "AAPL"),
      Fixtures.instruments, Fixtures.parties, reportingDate
    )
    val enriched = linked.collect { case LinkResult.Linked(e) => e }
    println(s"Linked trades: ${enriched.size}")
    println(s"Unlinked or orphan results: ${linked.size - enriched.size}")

    // Steps 3 and 4: build.
    val catReports = CatBuilder.build(enriched, "CAT")
    val mifirReports = MifirBuilder.build(enriched)
    println(s"CAT records built: ${catReports.size}")
    println(s"MiFIR records built: ${mifirReports.size}")

    // Step 5: validate.
    val (validCat, catViolations) = Validators.partitionCat(catReports)
    val (validMifir, mifirViolations) = Validators.partitionMifir(mifirReports)
    println(s"CAT valid: ${validCat.size}, violations: ${catViolations.size}")
    println(s"MiFIR valid: ${validMifir.size}, violations: ${mifirViolations.size}")
    mifirViolations.foreach(v =>
      println(s"  VIOLATION ${v.reportId} field=${v.field} rule=${v.rule}"))

    // Step 6: submission files.
    val catFile = SubmissionWriter.writeCat("CAT-20261031-001", validCat, reportingDate)
    val mifirFile = SubmissionWriter.writeMifir("MIFIR-20261031-001", validMifir, reportingDate)
    println(s"CAT file trailer: ${catFile.trailer}")
    println(s"MiFIR file trailer: ${mifirFile.trailer}")

    // Step 8: audit store.
    val store = new AuditStore
    validMifir.foreach { r =>
      store.append(AuditEntry.SubmittedVersion(
        r.transactionReferenceNumber, r.originalReportId, r.version,
        "MIFIR", ReportState.Submitted, Fixtures.t0, reportingDate,
        Map("tradeId" -> r.transactionReferenceNumber)
      ))
    }
    val (reported, missing) = store.completeness(
      List("TRD-1", "TRD-2", "TRD-3"), reportingDate, "MIFIR"
    )
    println(s"Completeness: reported=$reported missing=$missing")
  }
}
