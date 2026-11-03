package com.trading.reporting

import org.apache.spark.sql.{Dataset, SparkSession}
import java.time.LocalDate

object ReportingSparkJob {

  def run(
    spark: SparkSession,
    reportingDate: LocalDate,
    tradesPath: String,
    eventsPath: String,
    instrumentsPath: String,
    partiesPath: String,
    catOutputPath: String,
    mifirOutputPath: String,
    errorsPath: String
  ): Unit = {
    import spark.implicits._

    // Read inputs. In production these are Delta Lake tables; locally
    // they can be Parquet files with the same schema.
    val trades = spark.read.format("delta").load(tradesPath).as[Trade]
    val events = spark.read.format("delta").load(eventsPath).as[OrderEvent]
    val instruments = spark.read.format("delta").load(instrumentsPath)
      .as[InstrumentReference]
    val parties = spark.read.format("delta").load(partiesPath)
      .as[PartyReference]

    // Collect reference data to the driver: it is small relative to
    // trade volume, and the linker joins against it by key.
    val instrumentMap = instruments.collect().map(i => i.symbol -> i).toMap
    val partyMap = parties.collect().map(p => p.partyId -> p).toMap

    // Link on the driver for clarity; at production volume, replace
    // this with a distributed join keyed by order id.
    val tradesList = trades.collect().toList
    val eventsList = events.collect().toList
    val linked = OrderLinker.link(
      tradesList, eventsList, instrumentMap, partyMap, reportingDate
    )
    val enriched = linked.collect { case LinkResult.Linked(e) => e }

    // Build both regimes.
    val catReports = CatBuilder.build(enriched, "CAT")
    val mifirReports = MifirBuilder.build(enriched)

    // Validate before writing anything. Invalid reports go to the
    // error table, never to a submission table.
    val (validCat, catViolations) = Validators.partitionCat(catReports)
    val (validMifir, mifirViolations) = Validators.partitionMifir(mifirReports)

    // Write submission records.
    spark.createDataset(validCat)
      .write.format("delta").mode("append").save(catOutputPath)
    spark.createDataset(validMifir)
      .write.format("delta").mode("append").save(mifirOutputPath)

    // Write violations with their reporting date for the error queue.
    val allViolations = catViolations ++ mifirViolations
    spark.createDataset(allViolations)
      .write.format("delta").mode("append").save(errorsPath)

    println(s"CAT valid=${validCat.size} violations=${catViolations.size}")
    println(s"MiFIR valid=${validMifir.size} violations=${mifirViolations.size}")
  }
}
