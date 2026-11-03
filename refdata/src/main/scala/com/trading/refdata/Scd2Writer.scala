package com.trading.refdata

import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.functions._
import io.delta.tables.DeltaTable
import java.time.LocalDate

/** One row of the SCD Type 2 golden store. `effectiveTo` is None
  * for the current row. `changeReason` names the material fields
  * that changed relative to the previous row, or "INITIAL_LOAD".
  */
case class GoldenRow(
  goldenKey: String,
  name: String,
  cusip: Option[String],
  isin: Option[String],
  sedol: Option[String],
  figi: Option[String],
  ticker: Option[String],
  mic: Option[String],
  assetClass: String,
  securityType: String,
  currency: String,
  issuerName: String,
  issuerLei: Option[String],
  country: String,
  sector: String,
  status: String,
  effectiveFrom: LocalDate,
  effectiveTo: Option[LocalDate],
  isCurrent: Boolean,
  changeReason: String,
  winningSources: String
)

object Scd2Writer {
  val TablePath = "data/refdata/golden/security_master"

  /** Material fields, normalised, for change comparison. */
  def materialSignature(g: GoldenSecurity): String = {
    def norm(s: String): String =
      s.trim.replaceAll("\\s+", " ").replaceAll("[.,]$", "")
    Seq(
      norm(g.name.value),
      g.cusip.value.getOrElse(""),
      g.isin.value.getOrElse(""),
      g.sedol.value.getOrElse(""),
      g.figi.value.getOrElse(""),
      g.ticker.value.getOrElse(""),
      g.mic.value.getOrElse("").toUpperCase,
      g.assetClass.value.toUpperCase,
      g.securityType.value.toUpperCase,
      g.currency.value.toUpperCase,
      norm(g.issuerName.value),
      g.issuerLei.value.getOrElse(""),
      g.country.value.toUpperCase,
      norm(g.sector.value),
      g.status.value.toUpperCase
    ).mkString("|")
  }

  /** Which material fields changed, for the change_reason column. */
  def changedFields(oldRow: GoldenRow, g: GoldenSecurity): Seq[String] = {
    def norm(s: String): String =
      s.trim.replaceAll("\\s+", " ").replaceAll("[.,]$", "")
    val checks = Seq(
      ("name", norm(oldRow.name) != norm(g.name.value)),
      ("cusip", oldRow.cusip != g.cusip.value),
      ("isin", oldRow.isin != g.isin.value),
      ("sedol", oldRow.sedol != g.sedol.value),
      ("figi", oldRow.figi != g.figi.value),
      ("ticker", oldRow.ticker != g.ticker.value),
      ("mic", oldRow.mic != g.mic.value),
      ("assetClass", oldRow.assetClass.toUpperCase != g.assetClass.value.toUpperCase),
      ("securityType", oldRow.securityType.toUpperCase != g.securityType.value.toUpperCase),
      ("currency", oldRow.currency.toUpperCase != g.currency.value.toUpperCase),
      ("issuerName", norm(oldRow.issuerName) != norm(g.issuerName.value)),
      ("issuerLei", oldRow.issuerLei != g.issuerLei.value),
      ("country", oldRow.country.toUpperCase != g.country.value.toUpperCase),
      ("sector", norm(oldRow.sector) != norm(g.sector.value)),
      ("status", oldRow.status.toUpperCase != g.status.value.toUpperCase)
    )
    checks.filter(_._2).map(_._1)
  }

  def toRow(g: GoldenSecurity, effectiveFrom: LocalDate,
            changeReason: String): GoldenRow = {
    GoldenRow(
      goldenKey = g.goldenKey,
      name = g.name.value,
      cusip = g.cusip.value,
      isin = g.isin.value,
      sedol = g.sedol.value,
      figi = g.figi.value,
      ticker = g.ticker.value,
      mic = g.mic.value,
      assetClass = g.assetClass.value,
      securityType = g.securityType.value,
      currency = g.currency.value,
      issuerName = g.issuerName.value,
      issuerLei = g.issuerLei.value,
      country = g.country.value,
      sector = g.sector.value,
      status = g.status.value,
      effectiveFrom = effectiveFrom,
      effectiveTo = None,
      isCurrent = true,
      changeReason = changeReason,
      winningSources = Seq(
        "name" -> g.name.winningSource,
        "cusip" -> g.cusip.winningSource,
        "isin" -> g.isin.winningSource,
        "sector" -> g.sector.winningSource,
        "issuerLei" -> g.issuerLei.winningSource,
        "status" -> g.status.winningSource
      ).map { case (f, s) => s"$f=$s" }.mkString(",")
    )
  }

  /** Applies one load's survived securities to the golden store.
    * Returns (rowsClosed, rowsOpened, rowsUnchanged) for the load log.
    */
  def applyLoad(spark: SparkSession, survived: Seq[GoldenSecurity],
                effectiveDate: LocalDate): (Long, Long, Long) = {
    import spark.implicits._
    val tableExists = DeltaTable.isDeltaTable(spark, TablePath)
    if (!tableExists) {
      val initial = survived.map(g => toRow(g, effectiveDate, "INITIAL_LOAD"))
      initial.toDS().write.format("delta").save(TablePath)
      return (0L, initial.size.toLong, 0L)
    }

    val table = DeltaTable.forPath(spark, TablePath)
    val current = table.toDF.filter($"isCurrent" === true)
      .as[GoldenRow].collect().toSeq
    val currentByKey = current.map(r => r.goldenKey -> r).toMap

    var closed = 0L
    var opened = 0L
    var unchanged = 0L
    val newRows = scala.collection.mutable.ArrayBuffer.empty[GoldenRow]
    val keysToClose = scala.collection.mutable.ArrayBuffer.empty[String]

    for (g <- survived) {
      currentByKey.get(g.goldenKey) match {
        case None =>
          newRows += toRow(g, effectiveDate, "INITIAL_LOAD")
          opened += 1
        case Some(oldRow) =>
          val changed = changedFields(oldRow, g)
          if (changed.isEmpty) {
            unchanged += 1
          } else {
            keysToClose += g.goldenKey
            newRows += toRow(g, effectiveDate, changed.mkString(","))
            closed += 1
            opened += 1
          }
      }
    }

    if (keysToClose.nonEmpty) {
      val keysLit = keysToClose.map(k => s"'$k'").mkString(",")
      table.update(
        condition = expr(s"isCurrent = true AND goldenKey IN ($keysLit)"),
        set = Map(
          "effectiveTo" -> lit(effectiveDate.toString).cast("date"),
          "isCurrent" -> lit(false)
        )
      )
    }
    if (newRows.nonEmpty) {
      newRows.toDS().write.format("delta").mode("append").save(TablePath)
    }
    (closed, opened, unchanged)
  }
}
