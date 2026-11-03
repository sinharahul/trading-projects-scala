package com.trading.refdata

import java.time.LocalDate

/** Severity decides whether a finding blocks publication. */
sealed trait Severity
object Severity {
  case object Blocking extends Severity
  case object Warning extends Severity
}

/** One finding against one golden security in one load. */
case class DqFinding(
  goldenKey: String,
  checkName: String,
  severity: Severity,
  message: String
)

/** The scorecard for one load: per-check pass rates plus the
  * list of quarantined securities.
  */
case class DqScorecard(
  loadBatch: String,
  loadDate: LocalDate,
  totalRows: Long,
  quarantinedRows: Long,
  checkResults: Seq[CheckResult],
  quarantinedKeys: Seq[String]
)

case class CheckResult(
  checkName: String,
  passed: Long,
  failed: Long
) {
  def passRate: Double =
    if (passed + failed == 0) 1.0 else passed.toDouble / (passed + failed)
}

object DataQuality {

  private val IsoCurrencies = Set(
    "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK",
    "DKK", "PLN", "CZK", "HUF", "INR", "BRL", "MXN", "ZAR", "KRW", "SGD",
    "HKD", "CNY", "TWD", "THB", "MYR", "IDR", "PHP", "ILS", "AED", "SAR"
  )

  /** Row-level checks for one survived security. */
  def checkRow(g: GoldenSecurity,
               allGoldens: Seq[GoldenSecurity]): Seq[DqFinding] = {
    val findings = scala.collection.mutable.ArrayBuffer.empty[DqFinding]
    def block(check: String, msg: String): Unit =
      findings += DqFinding(g.goldenKey, check, Severity.Blocking, msg)
    def warn(check: String, msg: String): Unit =
      findings += DqFinding(g.goldenKey, check, Severity.Warning, msg)

    // Completeness: required fields.
    if (g.name.value.trim.isEmpty)
      block("completeness.name", "Security name is empty")
    val hasValidId = Seq(g.cusip.value, g.isin.value, g.sedol.value, g.figi.value)
      .flatten.nonEmpty
    if (!hasValidId)
      block("completeness.identifier",
        "No valid primary identifier (CUSIP/ISIN/SEDOL/FIGI)")
    if (g.currency.value.trim.isEmpty)
      block("completeness.currency", "Currency is empty")
    if (g.assetClass.value.trim.isEmpty)
      block("completeness.assetClass", "Asset class is empty")

    // Validity: check digits and domains.
    g.cusip.value.foreach { c =>
      if (!Validation.isValidCusip(c))
        block("validity.cusip", s"CUSIP $c fails its check digit")
    }
    g.isin.value.foreach { i =>
      if (!Validation.isValidIsin(i))
        block("validity.isin", s"ISIN $i fails its check digit")
    }
    g.sedol.value.foreach { s =>
      if (!Validation.isValidSedol(s))
        block("validity.sedol", s"SEDOL $s fails its check digit")
    }
    if (!IsoCurrencies.contains(g.currency.value.toUpperCase))
      block("validity.currency",
        s"Currency ${g.currency.value} is not a known ISO 4217 code")
    g.mic.value.foreach { m =>
      if (!Validation.isValidMic(m))
        warn("validity.mic", s"MIC $m is not a 4-character venue code")
    }
    g.issuerLei.value.foreach { lei =>
      if (!Validation.isValidLeiStructure(lei))
        warn("validity.lei", s"LEI $lei fails structural validation")
    }

    // Uniqueness: this security's identifiers must not appear on
    // any other golden security.
    val myIds = Seq(
      g.cusip.value.map("CUSIP" -> _),
      g.isin.value.map("ISIN" -> _),
      g.sedol.value.map("SEDOL" -> _),
      g.figi.value.map("FIGI" -> _)
    ).flatten
    for ((idType, value) <- myIds) {
      val collisions = allGoldens.filter { other =>
        other.goldenKey != g.goldenKey && {
          val otherIds = Seq(
            other.cusip.value.map("CUSIP" -> _),
            other.isin.value.map("ISIN" -> _),
            other.sedol.value.map("SEDOL" -> _),
            other.figi.value.map("FIGI" -> _)
          ).flatten
          otherIds.contains((idType, value))
        }
      }
      if (collisions.nonEmpty)
        block("uniqueness.identifier",
          s"$idType $value also appears on " +
          collisions.map(_.goldenKey).mkString(","))
    }

    // Cross-field sanity: a delisted security should not arrive
    // with a fresh active status from one vendor and a delisted
    // status from another without a status history to explain it.
    if (g.status.value.toUpperCase == "DELISTED" && g.ticker.value.isDefined)
      warn("sanity.delistedWithTicker",
        "Delisted security still carries a ticker; check listing status")

    findings.toSeq
  }

  /** Load-level checks: row-count swing and quarantine rate. */
  def checkLoad(totalRows: Long, previousTotalRows: Option[Long],
                quarantinedRows: Long): Seq[DqFinding] = {
    val findings = scala.collection.mutable.ArrayBuffer.empty[DqFinding]
    previousTotalRows.foreach { prev =>
      if (prev > 0) {
        val swing = (totalRows - prev).toDouble / prev
        if (swing < -0.20)
          findings += DqFinding("LOAD", "load.rowCountDrop", Severity.Blocking,
            f"Row count fell ${-swing * 100}%.1f%% vs previous load ($prev -> $totalRows)")
        else if (swing > 0.50)
          findings += DqFinding("LOAD", "load.rowCountSpike", Severity.Warning,
            f"Row count rose ${swing * 100}%.1f%% vs previous load ($prev -> $totalRows)")
      }
    }
    if (totalRows > 0 && quarantinedRows.toDouble / totalRows > 0.05)
      findings += DqFinding("LOAD", "load.quarantineRate", Severity.Blocking,
        f"Quarantine rate ${quarantinedRows.toDouble / totalRows * 100}%.1f%% exceeds 5%%")
    findings.toSeq
  }

  /** Builds the scorecard and returns the keys to quarantine. */
  def scorecard(loadBatch: String, loadDate: LocalDate,
                goldens: Seq[GoldenSecurity],
                previousTotalRows: Option[Long]): (DqScorecard, Seq[String]) = {
    val rowFindings = goldens.flatMap(g => checkRow(g, goldens))
    val loadFindings = checkLoad(goldens.size.toLong, previousTotalRows,
      rowFindings.count(f => f.severity == Severity.Blocking))
    val allFindings = rowFindings ++ loadFindings
    val quarantined = rowFindings
      .filter(_.severity == Severity.Blocking)
      .map(_.goldenKey).distinct
    val checkNames = allFindings.map(_.checkName).distinct
    val results = checkNames.map { name =>
      val failed = allFindings.count(_.checkName == name)
      // Passed count is measured per row for row checks.
      val passed = goldens.size.toLong - rowFindings.count(_.checkName == name)
      CheckResult(name, math.max(0L, passed), failed.toLong)
    }
    val card = DqScorecard(
      loadBatch = loadBatch,
      loadDate = loadDate,
      totalRows = goldens.size.toLong,
      quarantinedRows = quarantined.size.toLong,
      checkResults = results,
      quarantinedKeys = quarantined
    )
    (card, quarantined)
  }
}
