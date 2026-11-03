package com.backoffice.marketdata.generator

import java.time.Instant
import java.util.Properties
import org.apache.kafka.clients.producer.{
  KafkaProducer, ProducerConfig, ProducerRecord
}
import org.apache.kafka.common.serialization.StringSerializer

object KafkaProducerApp {

  def main(args: Array[String]): Unit = {
    val bootstrap = if (args.length > 0) args(0) else "localhost:9092"
    val topic = if (args.length > 1) args(1) else "market-data-raw"
    val count = if (args.length > 2) args(2).toInt else 2000
    val defectRate = if (args.length > 3) args(3).toDouble else 0.02

    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      classOf[StringSerializer].getName)
    // Wait for the leader to acknowledge, and retry transient failures.
    // acks=all with idempotence is the production-grade setting; for a
    // local single broker, acks=1 behaves the same and is easier to
    // reason about while learning. We use all here so the habit forms.
    props.put(ProducerConfig.ACKS_CONFIG, "all")
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
    props.put(ProducerConfig.RETRIES_CONFIG, "3")

    val producer = new KafkaProducer[String, String](props)
    try {
      val start = Instant.parse("2026-10-30T14:30:00Z")
      val ticks = TickGenerator.generate(count, start, defectRate = defectRate)
      var sent = 0
      ticks.foreach { tick =>
        val record = new ProducerRecord[String, String](
          topic, tick.symbol, TickGenerator.toJson(tick))
        producer.send(record)
        sent += 1
        if (sent % 500 == 0) println(s"Sent $sent records...")
      }
      producer.flush()
      println(s"Done. Sent $sent records to topic $topic " +
        s"(generated $count base ticks plus defects).")
    } finally {
      producer.close()
    }
  }
}
