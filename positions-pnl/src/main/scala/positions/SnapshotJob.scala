package positions

import org.apache.spark.sql.{SparkSession, SaveMode}
import org.apache.spark.sql.functions._
import java.sql.Timestamp

object SnapshotJob {
  def main(args: Array[String]): Unit = {
    val snapshotType = args.lift(0).getOrElse("INTRADAY")
    val spark = SparkSession.builder()
      .appName(s"position-snapshots-$snapshotType")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()
    import spark.implicits._

    val now = new Timestamp(System.currentTimeMillis())

    val positions = spark.read.format("delta")
      .load("/data/delta/positions_current")
      .as[PositionRow]

    val prices = spark.read.format("delta")
      .load("/data/delta/prices_latest")
      .select($"symbol", $"price".as("marketPrice"))

    val joined = positions
      .join(prices, Seq("symbol"), "left")
      .as[(PositionRow, Option[BigDecimal])]

    // Enrich in plain Scala on the driver side for clarity at this
    // scale; in production this enrichment runs as a Dataset map.
    val snapshots = joined.collect().flatMap { case (pos, priceOpt) =>
      MarkToMarket.enrich(pos.toState, priceOpt, now, snapshotType)
    }
    val unpriced = joined.collect()
      .filter(_._2.isEmpty).map(_._1)

    val snapshotDs = spark.createDataset(snapshots.toSeq)
    snapshotDs.write.format("delta")
      .mode(SaveMode.Append)
      .partitionBy("snapshotType")
      .save("/data/delta/position_snapshots")

    // Unpriced positions go to their own report table, never silently
    // dropped and never reported with a zero price.
    val unpricedDs = spark.createDataset(unpriced.toSeq)
    unpricedDs.write.format("delta")
      .mode(SaveMode.Append)
      .save("/data/delta/unpriced_positions")

    println(s"Snapshot [$snapshotType] written: " +
      s"${snapshots.length} positions, ${unpriced.length} unpriced.")
    spark.stop()
  }
}
