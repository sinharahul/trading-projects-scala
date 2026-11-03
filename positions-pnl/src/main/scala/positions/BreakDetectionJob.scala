package positions

import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.spark.sql.functions._
import java.sql.Date

object BreakDetectionJob {
  def main(args: Array[String]): Unit = {
    val snapshotDate = Date.valueOf(args.lift(0).getOrElse("2026-10-30"))
    val spark = SparkSession.builder()
      .appName("position-break-detection")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()
    import spark.implicits._

    // Our end-of-day positions for the date.
    val ours = spark.read.format("delta")
      .load("/data/delta/position_snapshots")
      .filter($"snapshotType" === "EOD")
      .filter($"snapshotTime".cast("date") === snapshotDate)
      .select($"account", $"symbol",
        $"quantity".as("actualQuantity"),
        $"marketValue".as("actualValue"))

    // Custodian file: account, symbol, quantity, market value.
    val custodian = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv("/data/custodian/positions.csv")
      .select($"account", $"symbol",
        $"quantity".as("expectedQuantity"),
        $"marketValue".as("expectedValue"))

    val breaks = ours.join(custodian, Seq("account", "symbol"), "full_outer")
      .withColumn("actualQuantity",
        coalesce($"actualQuantity", lit(0L)))
      .withColumn("expectedQuantity",
        coalesce($"expectedQuantity", lit(0L)))
      .withColumn("quantityDifference",
        $"expectedQuantity" - $"actualQuantity")
      .filter($"quantityDifference" =!= 0)
      .withColumn("snapshotDate", lit(snapshotDate))
      .withColumn("status", lit("OPEN"))
      .withColumn("reasonCode",
        when($"actualQuantity" === 0, "MISSING_IN_ENGINE")
          .when($"expectedQuantity" === 0, "MISSING_AT_CUSTODIAN")
          .otherwise("QUANTITY_MISMATCH"))
      .select("account", "symbol", "snapshotDate",
        "expectedQuantity", "actualQuantity", "quantityDifference",
        "expectedValue", "actualValue", "status", "reasonCode")

    breaks.write.format("delta")
      .mode(SaveMode.Append)
      .save("/data/delta/position_breaks")

    val count = breaks.count()
    println(s"Break detection for $snapshotDate: $count breaks found.")
    breaks.show(truncate = false)
    spark.stop()
  }
}
