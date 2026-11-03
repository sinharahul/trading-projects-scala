package com.trading.refdata

import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.functions._
import io.delta.tables.DeltaTable

/** A row of the published current view. */
case class PublishedSecurity(
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
  effectiveFrom: String,
  loadBatch: String
)

object Publisher {
  val CurrentViewPath = "data/refdata/published/security_current"
  val ChangeFeedTopic = "refdata.security.changes"

  /** Rebuilds the published current view from the SCD store,
    * excluding quarantined securities.
    */
  def publishCurrent(spark: SparkSession, quarantined: Set[String],
                     loadBatch: String): Long = {
    import spark.implicits._
    val golden = spark.read.format("delta")
      .load(Scd2Writer.TablePath)
      .filter($"isCurrent" === true)
      .as[GoldenRow]
    val published = golden
      .filter(r => !quarantined.contains(r.goldenKey))
      .map { r =>
        PublishedSecurity(
          goldenKey = r.goldenKey,
          name = r.name,
          cusip = r.cusip,
          isin = r.isin,
          sedol = r.sedol,
          figi = r.figi,
          ticker = r.ticker,
          mic = r.mic,
          assetClass = r.assetClass,
          securityType = r.securityType,
          currency = r.currency,
          issuerName = r.issuerName,
          issuerLei = r.issuerLei,
          country = r.country,
          sector = r.sector,
          status = r.status,
          effectiveFrom = r.effectiveFrom.toString,
          loadBatch = loadBatch
        )
      }
    published.write.format("delta").mode("overwrite").save(CurrentViewPath)
    published.count()
  }
}
