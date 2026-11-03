package com.trading.refdata

import org.apache.spark.sql.{Dataset, SparkSession}
import java.time.LocalDate
import java.security.MessageDigest

object VendorFeedA {
  private def opt(s: String): Option[String] =
    Option(s).map(_.trim).filter(_.nonEmpty)

  private def hashRow(values: Seq[String]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(values.mkString("|").getBytes("UTF-8"))
    bytes.map("%02x".format(_)).mkString
  }

  /** Reads Vendor A's CSV into staged VendorRecords. */
  def read(spark: SparkSession, path: String): Dataset[VendorRecord] = {
    import spark.implicits._
    val raw = spark.read
      .option("header", "true")
      .option("inferSchema", "false")
      .csv(path)
    raw.map { row =>
      def get(name: String): String = {
        val v = row.getAs[String](name)
        if (v == null) "" else v
      }
      val values = Seq(
        get("vendor_security_id"), get("name"), get("cusip"), get("isin"),
        get("sedol"), get("figi"), get("ticker"), get("mic"),
        get("asset_class"), get("security_type"), get("currency"),
        get("issuer_name"), get("issuer_lei"), get("country"),
        get("sector"), get("status"), get("as_of_date")
      )
      VendorRecord(
        vendor = "VENDOR_A",
        vendorSecurityId = get("vendor_security_id"),
        name = get("name"),
        cusip = opt(get("cusip")),
        isin = opt(get("isin")),
        sedol = opt(get("sedol")),
        figi = opt(get("figi")),
        ticker = opt(get("ticker")),
        mic = opt(get("mic")),
        assetClass = get("asset_class"),
        securityType = get("security_type"),
        currency = get("currency"),
        issuerName = get("issuer_name"),
        issuerLei = opt(get("issuer_lei")),
        country = get("country"),
        sector = get("sector"),
        status = get("status"),
        asOfDate = LocalDate.parse(get("as_of_date")),
        rawHash = hashRow(values)
      )
    }
  }
}
