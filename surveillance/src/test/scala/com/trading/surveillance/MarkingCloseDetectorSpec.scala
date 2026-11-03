package com.trading.surveillance

import com.trading.surveillance.detectors.MarkingCloseDetector
import com.trading.surveillance.model.Trade
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class MarkingCloseDetectorSpec extends AnyFlatSpec with Matchers {
  private lazy implicit val spark: SparkSession = SparkSession.builder()
    .master("local[*]")
    .appName("marking-close-detector-test")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  import spark.implicits._

  private def trade(
    id: String, qty: Long, price: Double, ts: String, owner: String
  ): Trade = Trade(
    tradeId = id, symbol = "VERT", price = price, quantity = qty,
    buyOrderId = s"B-$id", sellOrderId = s"S-$id",
    buyAccountId = "ACC-301", sellAccountId = "ACC-302",
    buyBeneficialOwnerId = owner, sellBeneficialOwnerId = "OWN-302",
    tradeTime = Timestamp.valueOf(ts)
  )

  "MarkingCloseDetector" should "fire on concentrated close-window activity" in {
    // Quiet morning: 10,000 shares. Close window: 30,000 shares, 75% from
    // one owner, price pushed from 45.00 to 46.00 (+2.2%).
    val morning = (1 to 10).map(i =>
      trade(s"M$i", 1000L, 45.00, f"2026-09-01 10:0$i:00", "OWN-001"))
    val close = (1 to 6).map(i =>
      trade(s"C$i", 5000L, 45.00 + i * 0.2, f"2026-09-01 15:5$i:00", "OWN-999"))
    val alerts = MarkingCloseDetector.detect((morning ++ close).toDF())
    alerts.count() should be >= 1L
  }

  it should "not fire on a busy but diffuse close" in {
    val morning = (1 to 10).map(i =>
      trade(s"M$i", 1000L, 45.00, f"2026-09-01 10:0$i:00", "OWN-001"))
    val close = (1 to 6).map { i =>
      trade(s"C$i", 5000L, 45.00, f"2026-09-01 15:5$i:00", s"OWN-D$i")
    }
    MarkingCloseDetector.detect((morning ++ close).toDF())
      .count() shouldBe 0L
  }
}
