package com.trading.surveillance.model

import org.apache.spark.sql.types._

object Schemas {
  val orderEventSchema: StructType = StructType(Seq(
    StructField("orderId", StringType, nullable = false),
    StructField("accountId", StringType, nullable = false),
    StructField("beneficialOwnerId", StringType, nullable = false),
    StructField("controllerId", StringType, nullable = false),
    StructField("symbol", StringType, nullable = false),
    StructField("side", StringType, nullable = false),
    StructField("orderType", StringType, nullable = false),
    StructField("quantity", LongType, nullable = false),
    StructField("price", DoubleType, nullable = false),
    StructField("eventType", StringType, nullable = false),
    StructField("eventTime", TimestampType, nullable = false),
    StructField("deviceFingerprint", StringType, nullable = true)
  ))

  val tradeSchema: StructType = StructType(Seq(
    StructField("tradeId", StringType, nullable = false),
    StructField("symbol", StringType, nullable = false),
    StructField("price", DoubleType, nullable = false),
    StructField("quantity", LongType, nullable = false),
    StructField("buyOrderId", StringType, nullable = false),
    StructField("sellOrderId", StringType, nullable = false),
    StructField("buyAccountId", StringType, nullable = false),
    StructField("sellAccountId", StringType, nullable = false),
    StructField("buyBeneficialOwnerId", StringType, nullable = false),
    StructField("sellBeneficialOwnerId", StringType, nullable = false),
    StructField("tradeTime", TimestampType, nullable = false),
    StructField("venue", StringType, nullable = false)
  ))
}
