package com.trading.surveillance

import com.trading.surveillance.detectors.UnusualVolumeDetector
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Date

class UnusualVolumeDetectorSpec extends AnyFlatSpec with Matchers {
  private lazy implicit val spark: SparkSession = SparkSession.builder()
    .master("local[*]")
    .appName("unusual-volume-detector-test")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  import spark.implicits._

  "UnusualVolumeDetector" should "fire on a 5-sigma day" in {
    val rows = (1 to 30).map(i =>
      ("VERT", Date.valueOf(f"2026-08-$i%02d"), 90000L)) ++
      Seq(("VERT", Date.valueOf("2026-09-01"), 900000L))
    val alerts = UnusualVolumeDetector.detect(rows.toDF("symbol", "day", "volume"))
    alerts.count() should be >= 1L
  }

  it should "not fire on an ordinary day" in {
    val rows = (1 to 31).map(i =>
      ("VERT", Date.valueOf(f"2026-08-$i%02d"), 90000L))
    UnusualVolumeDetector.detect(rows.toDF("symbol", "day", "volume"))
      .count() shouldBe 0L
  }
}
