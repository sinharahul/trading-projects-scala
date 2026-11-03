package com.trading.reporting

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReportingPipelineSpec extends AnyFlatSpec with Matchers {

  "OrderLinker" should "sort events by timestamp regardless of arrival order" in {
    val scrambled = Fixtures.eventsFor("ORD-1", "AAPL").reverse
    val lifecycle = OrderLinker.reconstructLifecycle(scrambled)
    lifecycle.map(_._1.eventType) shouldBe List(
      EventType.OrderReceived, EventType.OrderRouted, EventType.OrderExecuted
    )
  }

  it should "flag a trade with no execution event as unlinked" in {
    val noExecEvents = Fixtures.eventsFor("ORD-1", "AAPL")
      .filterNot(_.eventType == EventType.OrderExecuted)
    val results = OrderLinker.link(
      List(Fixtures.trade1), noExecEvents,
      Fixtures.instruments, Fixtures.parties, Fixtures.reportingDate
    )
    results.head shouldBe a[LinkResult.Unlinked]
  }

  "CatBuilder" should "produce one record per event with identical linkage keys" in {
    val reports = CatBuilder.build(Fixtures.enrichedLinked, "CAT")
    val ord1 = reports.filter(_.orderId == "ORD-1")
    ord1.size shouldBe 3
    ord1.map(_.linkageKey).toSet shouldBe Set("FIRM-A|ORD-1|2026-10-31")
    ord1.forall(!_.isLate) shouldBe true
  }

  it should "flag reports late when the reporting date is two days after the event" in {
    val late = CatBuilder.build(
      Fixtures.enrichedLinked.map(_.copy(reportingDate = LocalDate.of(2026, 11, 3))),
      "CAT"
    )
    late.forall(_.isLate) shouldBe true
  }

  "MifirBuilder" should "derive buyer as client and seller as counterparty for an agency buy" in {
    val reports = MifirBuilder.build(Fixtures.enrichedLinked)
    val r1 = reports.find(_.transactionReferenceNumber == "FIRM-A-TRD-1").get
    r1.buyerIdentification shouldBe "US19400101JOHN_DOE"
    r1.sellerIdentification shouldBe "549300ABCDEF12345678"
    r1.shortSellingIndicator shouldBe "NOSE"
    r1.instrumentIdentification shouldBe "US0378331005"
  }

  it should "derive buyer as firm for a principal short sale and set SESH" in {
    val reports = MifirBuilder.build(Fixtures.enrichedLinked)
    val r2 = reports.find(_.transactionReferenceNumber == "FIRM-A-TRD-2").get
    r2.buyerIdentification shouldBe "549300ABCDEF12345678"
    r2.sellerIdentification shouldBe "FIRM-A"
    r2.shortSellingIndicator shouldBe "SESH"
  }

  it should "produce exactly one record per trade" in {
    val reports = MifirBuilder.build(Fixtures.enrichedLinked)
    reports.size shouldBe 3
  }

  "Validators" should "reject a MiFIR record with no decision maker" in {
    val reports = MifirBuilder.build(Fixtures.enrichedLinked)
    val r3 = reports.find(_.transactionReferenceNumber == "FIRM-A-TRD-3").get
    Validators.validateMifir(r3) match {
      case ValidationResult.Invalid(_, violations) =>
        violations.map(_.field) should contain("investmentDecisionMaker")
      case ValidationResult.Valid(_) =>
        fail("expected validation failure for missing decision maker")
    }
  }

  it should "reject a CAT execution record with no price" in {
    val bad = CatReport(
      "FIRM-A", "ORD-9", "OrderExecuted",
      Fixtures.t0, "AAPL", "B", 100, None, "A",
      Fixtures.reportingDate, "FIRM-A|ORD-9|2026-10-31", isLate = false,
      version = 1, originalReportId = None
    )
    Validators.validateCat(bad) match {
      case ValidationResult.Invalid(_, violations) =>
        violations.map(_.rule) should contain("CROSS_FIELD")
      case ValidationResult.Valid(_) =>
        fail("expected validation failure for execution without price")
    }
  }

  it should "reject a MiFIR record where buyer equals seller" in {
    val bad = MifirReport(
      "FIRM-A-TRD-9", "NASDAQ", "US0378331005", BigDecimal("100.00"), 10,
      Fixtures.t0, "FIRM-A", "FIRM-A", "DM-1", "TR-1", "US",
      "false", "NOSE", "false", Fixtures.reportingDate, 1, None
    )
    Validators.validateMifir(bad) match {
      case ValidationResult.Invalid(_, violations) =>
        violations.map(_.field) should contain("buyerIdentification")
      case ValidationResult.Valid(_) =>
        fail("expected validation failure for buyer equals seller")
    }
  }

  "SubmissionWriter" should "produce correct CAT control totals for the sample order" in {
    val cat = CatBuilder.build(Fixtures.enrichedLinked, "CAT")
      .filter(_.orderId == "ORD-1")
    val file = SubmissionWriter.writeCat("CAT-20261031-001", cat, Fixtures.reportingDate)
    file.totals.recordCount shouldBe 3
    // Event quantities: 1000 + 1000 + 800 = 2800; notional from the execution only.
    file.totals.totalQuantity shouldBe 2800
    file.totals.totalNotional shouldBe BigDecimal("140200")
    file.trailer shouldBe "TRL|3|2800|140200"
  }

  it should "produce correct MiFIR control totals for the sample trades" in {
    val mifir = MifirBuilder.build(Fixtures.enrichedLinked)
    val (valid, _) = Validators.partitionMifir(mifir)
    val file = SubmissionWriter.writeMifir("MIFIR-20261031-001", valid, Fixtures.reportingDate)
    // Only trades 1 and 2 are valid; trade 3 fails on decision maker.
    file.totals.recordCount shouldBe 2
    file.totals.totalQuantity shouldBe 850
    // 800 * 175.25 + 50 * 4.80 = 140200 + 240 = 140440
    file.totals.totalNotional shouldBe BigDecimal("140440")
  }

  "AckProcessor" should "run a full rejection-correction-acceptance cycle" in {
    val v1 = ReportVersion(
      "FIRM-A-TRD-1", None, 1, ReportState.Submitted,
      List((ReportState.Submitted, Fixtures.t0, "submitted")),
      List.empty
    )
    val rejected = AckProcessor.applyAck(
      v1,
      Acknowledgement("FIRM-A-TRD-1", AckStatus.Rejected,
        List("DECISION_MAKER_MISSING"), Fixtures.t0.plusSeconds(3600))
    )
    rejected.state shouldBe ReportState.Rejected
    val (superseded, v2) = AckProcessor.correct(
      rejected, Fixtures.t0.plusSeconds(7200), "decision maker populated from OMS"
    )
    superseded.state shouldBe ReportState.Corrected
    v2.version shouldBe 2
    v2.originalReportId shouldBe Some("FIRM-A-TRD-1")
    val accepted = AckProcessor.applyAck(
      v2.copy(state = ReportState.Resubmitted),
      Acknowledgement(v2.reportId, AckStatus.Accepted, List.empty,
        Fixtures.t0.plusSeconds(10800))
    )
    accepted.state shouldBe ReportState.Accepted
  }

  it should "flag submissions missing acknowledgements after the window" in {
    val missing = AckProcessor.missingAcks(
      List(("R1", Fixtures.t0), ("R2", Fixtures.t0)),
      Set("R1"),
      Fixtures.t0.plusSeconds(7200),
      windowMinutes = 60
    )
    missing shouldBe List("R2")
  }

  "AuditStore" should "return every version of a corrected report" in {
    val store = new AuditStore
    store.append(AuditEntry.SubmittedVersion(
      "FIRM-A-TRD-1", None, 1, "MIFIR", ReportState.Corrected,
      Fixtures.t0, Fixtures.reportingDate, Map("tradeId" -> "TRD-1")
    ))
    store.append(AuditEntry.SubmittedVersion(
      "FIRM-A-TRD-1-v2", Some("FIRM-A-TRD-1"), 2, "MIFIR", ReportState.Accepted,
      Fixtures.t0.plusSeconds(7200), Fixtures.reportingDate, Map("tradeId" -> "TRD-1")
    ))
    store.versionsOf("FIRM-A-TRD-1").map(_.version) shouldBe List(1, 2)
  }

  it should "flag a trade that was never submitted in the completeness check" in {
    val store = new AuditStore
    store.append(AuditEntry.SubmittedVersion(
      "FIRM-A-TRD-1", None, 1, "MIFIR", ReportState.Accepted,
      Fixtures.t0, Fixtures.reportingDate, Map("tradeId" -> "TRD-1")
    ))
    val (reported, missing) = store.completeness(
      List("TRD-1", "TRD-9"), Fixtures.reportingDate, "MIFIR"
    )
    reported shouldBe List("TRD-1")
    missing shouldBe List("TRD-9")
  }
}
