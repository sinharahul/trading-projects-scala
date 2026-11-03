package com.trading.surveillance.backtest

import org.apache.spark.sql.SparkSession

object BacktestMain {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("surveillance-backtest")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "8")
      .getOrCreate()
    BacktestHarness.run(spark)
    spark.stop()
  }
}
