package com.trading.refdata

import org.apache.spark.sql.SparkSession
import io.delta.tables._

object SparkEnv {
  def session(appName: String): SparkSession = {
    SparkSession.builder()
      .appName(appName)
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("spark.sql.shuffle.partitions", "8")
      .getOrCreate()
  }
}
