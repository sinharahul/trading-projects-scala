package com.trading.surveillance

import com.trading.surveillance.detectors.WashTradeDetector
import com.trading.surveillance.model.Trade
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class WashTradeDetectorSpec extends AnyFlatSpec with Matchers {
  private lazy implicit val spark: SparkSession = SparkSession.builder()
    .master("local[*]")
    .appName("wash-trade-detector-test")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  import spark.implicits._

  private def trade(
    id: String, ownerBuy: String, ownerSell: String, ts: String
  ): Trade = Trade(
    tradeId = id, symbol = "ACME", price = 150.0, quantity = 1000L,
    buyOrderId = s"B-$id", sellOrderId = s"S-$id",
    buyAccountId = "ACC-001", sellAccountId = "ACC-002",
    buyBeneficialOwnerId = ownerBuy, sellBeneficialOwnerId = ownerSell,
    tradeTime = Timestamp.valueOf(ts)
  )

  "WashTradeDetector" should "fire on three same-owner trades in five minutes" in {
    val trades = Seq(
      trade("T1", "OWN-X", "OWN-X", "2026-09-01 10:00:00"),
      trade("T2", "OWN-X", "OWN-X", "2026-09-01 10:01:00"),
      trade("T3", "OWN-X", "OWN-X", "2026-09-01 10:03:00")
    ).toDF()
    val alerts = WashTradeDetector.detect(trades)
    alerts.count() should be >= 1L
  }

  it should "not fire on a single same-owner trade" in {
    val trades = Seq(
      trade("T1", "OWN-X", "OWN-X", "2026-09-01 10:00:00")
    ).toDF()
    WashTradeDetector.detect(trades).count() shouldBe 0L
  }

  it should "not fire when owners differ" in {
    val trades = Seq(
      trade("T1", "OWN-X", "OWN-Y", "2026-09-01 10:00:00"),
      trade("T2", "OWN-X", "OWN-Y", "2026-09-01 10:01:00"),
      trade("T3", "OWN-X", "OWN-Y", "2026-09-01 10:02:00")
    ).toDF()
    WashTradeDetector.detect(trades).count() shouldBe 0L
  }

  it should "fire on one very large same-owner trade" in {
    val big = trade("T1", "OWN-X", "OWN-X", "2026-09-01 10:00:00")
      .copy(quantity = 100000L)
    WashTradeDetector.detect(Seq(big).toDF()).count() should be >= 1L
  }
}
