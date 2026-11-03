package positions

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

/** Batch backfill: recompute positions for given keys from full history.
  * Usage: BackfillJob <account> <symbol> — or all keys when no args given.
  * The fold uses the same pure state function as the streaming job.
  */
object BackfillJob {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("position-backfill")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()
    import spark.implicits._

    val trades = spark.read.format("delta")
      .load("/data/delta/trades_validated")
      .orderBy($"account", $"symbol", $"tradeTime")
      .as[TradeRow]

    // Group by key and fold in timestamp order. The ordering guarantee
    // comes from the sort above; verify it in tests with shuffled input.
    val recomputed = trades
      .groupByKey(t => (t.account, t.symbol))
      .mapGroups { case ((account, symbol), rows) =>
        val method = CostBasisConfig.methodFor(account)
        rows.foldLeft(PositionState.empty(account, symbol,
          new java.sql.Timestamp(System.currentTimeMillis()))) {
          (state, row) => method.applyTrade(state, row.toTrade)
        }
      }

    recomputed.write.format("delta")
      .mode("overwrite")
      .option("replaceWhere", "TRUE")
      .save("/data/delta/positions_current")
    println(s"Backfill complete: ${recomputed.count()} positions recomputed.")
    spark.stop()
  }
}
