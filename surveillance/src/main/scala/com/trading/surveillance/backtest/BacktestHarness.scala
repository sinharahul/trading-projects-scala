package com.trading.surveillance.backtest

import com.trading.surveillance.detectors._
import com.trading.surveillance.model.Alert
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.time.LocalDate

case class DetectorMetrics(
  detector: String,
  planted: Int,
  detected: Int,
  falsePositives: Int,
  lookalikeAlerts: Int,
  precision: Double,
  recall: Double
)

object BacktestHarness {
  /** Match alerts to planted scenarios by detector, symbol, and window overlap. */
  def matchAlerts(
    alerts: Seq[Alert],
    scenarios: Seq[PlantedScenario]
  ): (Set[String], Set[String]) = {
    val matchedScenarios = scenarios.filter { s =>
      alerts.exists { a =>
        a.detector == s.detector &&
        a.symbol == s.symbol &&
        !a.windowEnd.before(s.windowStart) &&
        !a.windowStart.after(s.windowEnd)
      }
    }.map(_.scenarioId).toSet
    val matchedAlertIds = alerts.filter { a =>
      scenarios.exists { s =>
        a.detector == s.detector &&
        a.symbol == s.symbol &&
        !a.windowEnd.before(s.windowStart) &&
        !a.windowStart.after(s.windowEnd)
      }
    }.map(_.alertId).toSet
    (matchedScenarios, matchedAlertIds)
  }

  /** Compute per-detector metrics from alerts and ground truth. */
  def metrics(
    alerts: Seq[Alert],
    scenarios: Seq[PlantedScenario]
  ): Seq[DetectorMetrics] = {
    val detectors = (alerts.map(_.detector) ++ scenarios.map(_.detector))
      .distinct.sorted
    detectors.map { d =>
      val dAlerts = alerts.filter(_.detector == d)
      val dScenarios = scenarios.filter(_.detector == d)
      val (matchedScenarios, matchedAlertIds) =
        matchAlerts(dAlerts, dScenarios)
      val falsePositives = dAlerts.count(a => !matchedAlertIds.contains(a.alertId))
      val detected = matchedScenarios.size
      val planted = dScenarios.size
      val precision =
        if (dAlerts.isEmpty) 1.0
        else matchedAlertIds.size.toDouble / dAlerts.size
      val recall =
        if (planted == 0) 1.0
        else detected.toDouble / planted
      DetectorMetrics(
        detector = d,
        planted = planted,
        detected = detected,
        falsePositives = falsePositives,
        lookalikeAlerts = 0,
        precision = precision,
        recall = recall
      )
    }
  }

  /** Run the full backtest: generate a month, plant scenarios, run detectors,
    * and print a metrics table. */
  def run(
    spark: SparkSession,
    seed: Long = 42L,
    monthStart: LocalDate = LocalDate.of(2026, 9, 1)
  ): Seq[DetectorMetrics] = {
    import spark.implicits._
    val gen = new SyntheticDataGenerator(seed = seed)
    val days = (0 until 22).map(monthStart.plusDays(_))

    // Normal flow for the whole month, all symbols.
    val normalOrders = days.flatMap { d =>
      gen.symbols.flatMap(s => gen.normalDay(s, d)._1)
    }
    val normalTrades = days.flatMap { d =>
      gen.symbols.flatMap(s => gen.normalDay(s, d)._2)
    }

    // Planted scenarios on specific days.
    val (washTrades, washScenario) =
      PlantedScenarios.washRing(monthStart.plusDays(4))
    val (spoofEvents, spoofScenario) =
      PlantedScenarios.spoofingEpisode(monthStart.plusDays(9))
    val (markTrades, markScenario) =
      PlantedScenarios.markingClose(monthStart.plusDays(14))
    val (volTrades, volScenario) =
      PlantedScenarios.volumeSpike(monthStart.plusDays(19))
    val lookalike = PlantedScenarios.marketMakerLookalike(monthStart.plusDays(9))

    val allTrades = normalTrades ++ washTrades ++ markTrades ++ volTrades
    val allOrders = normalOrders ++ spoofEvents ++ lookalike
    val scenarios = Seq(washScenario, spoofScenario, markScenario, volScenario)

    val tradesDF = allTrades.toDF()
    val ordersDF = allOrders.toDF()

    // Run each detector over the full month.
    val washAlerts = WashTradeDetector.detect(tradesDF)(spark)
      .as[Alert].collect().toSeq
    val spoofAlerts = SpoofingDetector.detect(ordersDF,
      Map("ACME" -> 152.40, "GLOB" -> 87.15, "INNO" -> 210.80,
        "VERT" -> 45.30, "NOVA" -> 310.55))(spark)
      .as[Alert].collect().toSeq
    val markAlerts = MarkingCloseDetector.detect(tradesDF)(spark)
      .as[Alert].collect().toSeq

    // Daily volumes for the unusual-volume detector.
    val dailyVolumes = tradesDF
      .withColumn("day", to_date(col("tradeTime")))
      .groupBy(col("symbol"), col("day"))
      .agg(sum("quantity").as("volume"))
    val volAlerts = UnusualVolumeDetector.detect(dailyVolumes)(spark)
      .as[Alert].collect().toSeq

    val allAlerts = washAlerts ++ spoofAlerts ++ markAlerts ++ volAlerts
    val results = metrics(allAlerts, scenarios)

    println("=" * 72)
    println("BACKTEST RESULTS")
    println("=" * 72)
    println(f"${"Detector"}%-18s ${"Planted"}%8s ${"Caught"}%8s " +
      f"${"FalsePos"}%9s ${"Precision"}%10s ${"Recall"}%8s")
    results.foreach { m =>
      println(f"${m.detector}%-18s ${m.planted}%8d ${m.detected}%8d " +
        f"${m.falsePositives}%9d ${m.precision * 100}%9.1f%% ${m.recall * 100}%7.1f%%")
    }
    println("=" * 72)
    results
  }
}
