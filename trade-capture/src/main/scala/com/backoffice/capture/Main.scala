package com.backoffice.capture

import com.backoffice.capture.enrichment.{Enricher, ReferenceDataReader}
import com.backoffice.capture.metrics.Metrics
import com.backoffice.capture.model._
import com.backoffice.capture.parsing.FixParser
import com.backoffice.capture.sink.{DeadLetter, DeadLetterQueue, DeltaSink}
import com.backoffice.capture.validation.{ValidationContext, Validator}
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import scala.jdk.CollectionConverters._

object Main {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val kafkaConf = config.getConfig("capture.kafka")
    val pgConf = config.getConfig("capture.postgres")
    val deltaConf = config.getConfig("capture.delta")
    val validationConf = config.getConfig("capture.validation")

    val spark = SparkSession.builder()
      .appName("trade-capture")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()

    val tablePath =
      s"${deltaConf.getString("warehouse")}/${deltaConf.getString("validated-table")}"
    DeltaSink.ensureTable(spark, tablePath)

    val refReader = new ReferenceDataReader(
      pgConf.getString("url"), pgConf.getString("user"), pgConf.getString("password"))
    var snapshot = refReader.load()
    var lastRefresh = System.currentTimeMillis()

    val dlq = new DeadLetterQueue(
      pgConf.getString("url"), pgConf.getString("user"), pgConf.getString("password"),
      kafkaConf.getString("bootstrap-servers"), kafkaConf.getString("dlq-topic"))

    val metrics = new Metrics(
      alertThresholdPct = validationConf.getDouble("reject-rate-alert-pct"),
      windowMinutes = validationConf.getInt("reject-rate-window-minutes"))

    val props = new Properties()
    props.put("bootstrap.servers", kafkaConf.getString("bootstrap-servers"))
    props.put("group.id", kafkaConf.getString("group-id"))
    props.put("key.deserializer", classOf[StringDeserializer].getName)
    props.put("value.deserializer", classOf[StringDeserializer].getName)
    props.put("enable.auto.commit", "false")
    props.put("auto.offset.reset", kafkaConf.getString("auto-offset-reset"))

    val consumer = new KafkaConsumer[String, String](props)
    consumer.subscribe(List(kafkaConf.getString("executions-topic")).asJava)

    log.info("Trade capture service started, consuming {}", kafkaConf.getString("executions-topic"))

    try {
      while (true) {
        // Refresh reference data every five minutes.
        if (System.currentTimeMillis() - lastRefresh > 5 * 60 * 1000) {
          snapshot = refReader.load()
          lastRefresh = System.currentTimeMillis()
          log.info("Reference data refreshed: {} securities", snapshot.securities.size)
        }

        val records = consumer.poll(Duration.ofMillis(1000))
        if (!records.isEmpty) {
          val ctx = ValidationContext(
            securities = snapshot.securities,
            approvedCounterparties = snapshot.approvedCounterparties,
            suspendedCounterparties = snapshot.suspendedCounterparties,
            tradingDays = snapshot.tradingDays,
            existingExecIds = bookedExecIds(spark, tablePath),
            priceTolerancePct = validationConf.getDouble("price-tolerance-pct"),
            maxQuantity = validationConf.getLong("max-quantity")
          )

          val enriched = scala.collection.mutable.ListBuffer.empty[EnrichedTrade]
          records.asScala.foreach { record =>
            metrics.recordReceived()
            FixParser.parse(record.value()) match {
              case Left(err) =>
                metrics.recordRejected("PARSE_ERROR")
                dlq.send(DeadLetter(
                  execId = extractExecId(record.value()).getOrElse("UNKNOWN"),
                  rawMessage = record.value(),
                  rejection = Rejection("PARSE_ERROR", err),
                  parsedSymbol = None))
              case Right(parsed) =>
                Validator.validate(parsed, ctx) match {
                  case Left(rejection) =>
                    metrics.recordRejected(rejection.code)
                    dlq.send(DeadLetter(
                      execId = parsed.execId,
                      rawMessage = record.value(),
                      rejection = rejection,
                      parsedSymbol = Some(parsed.symbol)))
                  case Right(trade) =>
                    Enricher.enrich(trade, snapshot) match {
                      case Left(err) =>
                        metrics.recordRejected("ENRICHMENT_FAILED")
                        dlq.send(DeadLetter(
                          execId = parsed.execId,
                          rawMessage = record.value(),
                          rejection = Rejection("ENRICHMENT_FAILED", err),
                          parsedSymbol = Some(parsed.symbol)))
                      case Right(e) =>
                        enriched += e
                        metrics.recordAccepted()
                    }
                }
            }
          }

          val (inserted, updated, skipped) =
            DeltaSink.mergeBatch(spark, tablePath, enriched.toSeq)
          log.info("Batch complete: received={} inserted={} updated={} skipped={}",
            records.count(), inserted, updated, skipped)

          // Commit ONLY after the sink has flushed. This ordering is the
          // second half of the idempotency guarantee: a crash before the
          // commit redelivers the batch, and the merge makes redelivery safe.
          consumer.commitSync()
        }
      }
    } finally {
      consumer.close()
      dlq.close()
      spark.stop()
    }
  }

  private def bookedExecIds(spark: SparkSession, tablePath: String): Set[String] = {
    import spark.implicits._
    val table = io.delta.tables.DeltaTable.forPath(spark, tablePath)
    table.toDF.select("exec_id").as[String].collect().toSet
  }

  private def extractExecId(raw: String): Option[String] = {
    raw.split("\\|").find(_.startsWith("17=")).map(_.substring(3))
  }
}
