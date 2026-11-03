package com.trading.refdata

import java.util.Properties
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

object KafkaChangePublisher {
  private val props = new Properties()
  props.put("bootstrap.servers", "localhost:9092")
  props.put("key.serializer",
    "org.apache.kafka.common.serialization.StringSerializer")
  props.put("value.serializer",
    "org.apache.kafka.common.serialization.StringSerializer")
  // Idempotence plus all-acknowledgements: a change event must
  // neither be lost nor duplicated within the producer's control.
  props.put("enable.idempotence", "true")
  props.put("acks", "all")

  private lazy val producer = new KafkaProducer[String, String](props)

  /** Publishes one event, keyed by golden key so that all events
    * for a security land on the same partition in order.
    */
  def publish(event: SecurityChangeEvent): Unit = {
    val record = new ProducerRecord[String, String](
      Publisher.ChangeFeedTopic, event.goldenKey, ChangeFeed.toJson(event))
    producer.send(record).get() // synchronous in this project: a
    // failed publish must fail the load, not vanish into a callback
  }

  def close(): Unit = producer.close()
}
