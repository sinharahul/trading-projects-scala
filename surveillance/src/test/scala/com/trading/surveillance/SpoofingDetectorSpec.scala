package com.trading.surveillance

import com.trading.surveillance.detectors.SpoofingDetector
import com.trading.surveillance.model.OrderEvent
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Timestamp

class SpoofingDetectorSpec extends AnyFlatSpec with Matchers {
  private lazy implicit val spark: SparkSession = SparkSession.builder()
    .master("local[*]")
    .appName("spoofing-detector-test")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  import spark.implicits._

  private def placed(id: String, ts: String): OrderEvent = OrderEvent(
    orderId = id, accountId = "ACC-101", beneficialOwnerId = "OWN-101",
    controllerId = "CTRL-101", symbol = "ACME", side = "SELL",
    orderType = "LIMIT", quantity = 50000L, price = 150.10,
    eventType = "PLACED", eventTime = Timestamp.valueOf(ts)
  )
  private def cancelled(id: String, ts: String): OrderEvent =
    placed(id, ts).copy(eventType = "CANCELLED")

  "SpoofingDetector" should "fire on repeated fast cancels without fills" in {
    val events = (1 to 6).flatMap { i =>
      Seq(
        placed(s"S$i", f"2026-09-01 10:0$i:00"),
        cancelled(s"S$i", f"2026-09-01 10:0$i:05")
      )
    }
    val alerts = SpoofingDetector.detect(
      events.toDF(), Map("ACME" -> 150.00))
    alerts.count() should be >= 1L
  }

  it should "not fire when the orders get filled" in {
    val events = (1 to 6).flatMap { i =>
      Seq(
        placed(s"S$i", f"2026-09-01 10:0$i:00"),
        placed(s"S$i", f"2026-09-01 10:0$i:02").copy(eventType = "FILLED")
      )
    }
    SpoofingDetector.detect(events.toDF(), Map("ACME" -> 150.00))
      .count() shouldBe 0L
  }

  it should "not fire on slow cancels" in {
    val events = (1 to 6).flatMap { i =>
      Seq(
        placed(s"S$i", f"2026-09-01 10:0$i:00"),
        cancelled(s"S$i", f"2026-09-01 10:${i + 10}:00")
      )
    }
    SpoofingDetector.detect(events.toDF(), Map("ACME" -> 150.00))
      .count() shouldBe 0L
  }
}
