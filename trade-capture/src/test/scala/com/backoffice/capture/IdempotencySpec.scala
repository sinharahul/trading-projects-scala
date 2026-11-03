package com.backoffice.capture

import com.backoffice.capture.model._
import com.backoffice.capture.sink.DeltaSink
import java.time.LocalDate
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

class IdempotencySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private lazy val spark: SparkSession = SparkSession.builder()
    .appName("idempotency-test")
    .master("local[*]")
    .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
    .config("spark.sql.catalog.spark_catalog",
      "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    .getOrCreate()

  override def afterAll(): Unit = spark.stop()

  private def enrichedTrade(
    execId: String,
    qty: Long = 2500L,
    px: BigDecimal = BigDecimal("227.45"),
    execType: ExecType = ExecType.Trade
  ): EnrichedTrade = EnrichedTrade(
    Trade(execId, "ORD-1", "AAPL", Side.Buy, execType, qty, px,
      LocalDate.of(2026, 10, 30), "EXCHANGE_X"),
    "NASDAQ", "USD", BigDecimal(qty) * px, LocalDate.of(2026, 11, 2)
  )

  private def rowCount(path: String): Long =
    spark.read.format("delta").load(path).count()

  private def priceFor(path: String, execId: String): BigDecimal = {
    import spark.implicits._
    val rows = spark.read.format("delta").load(path)
      .filter($"exec_id" === execId)
      .select("price").as[java.math.BigDecimal].collect()
    require(rows.length == 1, s"Expected exactly one row for $execId, got ${rows.length}")
    BigDecimal(rows.head.toString)
  }

  "DeltaSink" should "insert a new trade once" in {
    val path = "/tmp/delta-test-insert"
    DeltaSink.ensureTable(spark, path)
    DeltaSink.mergeBatch(spark, path, Seq(enrichedTrade("EXEC-A")))
    rowCount(path) shouldBe 1
  }

  it should "not duplicate a redelivered trade" in {
    val path = "/tmp/delta-test-duplicate"
    DeltaSink.ensureTable(spark, path)
    DeltaSink.mergeBatch(spark, path, Seq(enrichedTrade("EXEC-B")))
    DeltaSink.mergeBatch(spark, path, Seq(enrichedTrade("EXEC-B")))
    DeltaSink.mergeBatch(spark, path, Seq(enrichedTrade("EXEC-B")))
    rowCount(path) shouldBe 1
  }

  it should "apply an amendment from a TradeCorrect" in {
    val path = "/tmp/delta-test-amend"
    DeltaSink.ensureTable(spark, path)
    DeltaSink.mergeBatch(spark, path, Seq(enrichedTrade("EXEC-C")))
    DeltaSink.mergeBatch(spark, path,
      Seq(enrichedTrade("EXEC-C", px = BigDecimal("228.00"), execType = ExecType.TradeCorrect)))
    rowCount(path) shouldBe 1
    priceFor(path, "EXEC-C") shouldBe BigDecimal("228.00")
  }

  it should "survive a simulated crash and restart" in {
    // Simulate: batch processed, commit lost, batch reprocessed.
    val path = "/tmp/delta-test-crash"
    DeltaSink.ensureTable(spark, path)
    val batch = Seq(enrichedTrade("EXEC-D1"), enrichedTrade("EXEC-D2"))
    DeltaSink.mergeBatch(spark, path, batch)
    DeltaSink.mergeBatch(spark, path, batch) // redelivery after lost commit
    rowCount(path) shouldBe 2
  }
}
