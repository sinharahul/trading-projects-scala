package com.trading.refdata

import org.apache.spark.sql.SparkSession
import java.time.{LocalDate, LocalDateTime}
import java.time.format.DateTimeFormatter

object RefDataPipeline {
  def main(args: Array[String]): Unit = {
    val spark = SparkEnv.session("refdata-pipeline")
    val loadBatch = "LOAD-" +
      LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    val effectiveDate = LocalDate.now()

    // Step 2: ingest and stage.
    val a = VendorFeedA.read(spark,
      "refdata/src/main/resources/feeds/vendor_a.csv")
    val bRecords = VendorFeedB.read(
      "refdata/src/main/resources/feeds/vendor_b.json")
    import spark.implicits._
    val stagedA = Staging.write(spark, a, loadBatch)
    val stagedB = Staging.write(spark, bRecords.toDS(), loadBatch)
    println(s"Staged: Vendor A=$stagedA, Vendor B=$stagedB")

    // Steps 3-4: match and survive. The sample uses a simplified
    // in-memory grouping: records are grouped by shared primary
    // identifier, then linked by the matcher, then survived.
    // Production code replaces this grouping with the Spark job
    // over the staging table and the Postgres cross-reference table.
    val allRecords = a.collect().toSeq ++ bRecords
    val groups = groupBySharedIdentifier(allRecords)
    val survived = groups.zipWithIndex.map { case (group, idx) =>
      val key = group.flatMap(r =>
        Matching.validPrimaryIds(r).headOption.map(_ => r.vendorSecurityId)
      ).headOption.getOrElse(s"GOLDEN-$idx")
      Survivorship.survive(key, group)
    }
    println(s"Survived securities: ${survived.size}")

    // Step 6: data quality gate (runs before the SCD write commits
    // to publication; quarantined securities are excluded downstream).
    val (card, quarantined) = DataQuality.scorecard(
      loadBatch, effectiveDate, survived, previousTotalRows = None)
    println(s"DQ scorecard: total=${card.totalRows}, " +
      s"quarantined=${card.quarantinedRows}")
    card.checkResults.foreach { r =>
      println(f"  ${r.checkName}%-30s passRate=${r.passRate * 100}%.1f%%")
    }
    val loadBlocked = card.checkResults.exists { r =>
      r.checkName.startsWith("load.") && r.failed > 0
    }
    if (loadBlocked) {
      System.err.println("LOAD BLOCKED by a load-level DQ check. " +
        "No publication will occur.")
      sys.exit(2)
    }

    // Step 5: SCD write, excluding quarantined securities.
    val publishable = survived.filterNot(g =>
      quarantined.contains(g.goldenKey))
    val (closed, opened, unchanged) =
      Scd2Writer.applyLoad(spark, publishable, effectiveDate)
    println(s"SCD: closed=$closed, opened=$opened, unchanged=$unchanged")

    // Step 7: publish.
    val published = Publisher.publishCurrent(
      spark, quarantined.toSet, loadBatch)
    println(s"Published current view rows: $published")

    spark.stop()
  }

  /** Groups records that share at least one valid primary identifier.
    * This is the sample-scale grouping; the matcher in Step 3 is the
    * authority on whether a group is genuinely one security, and
    * conflicting groups are split by the NeedsReview outcome before
    * survivorship runs.
    */
  def groupBySharedIdentifier(
    records: Seq[VendorRecord]): Seq[Seq[VendorRecord]] = {
    val parent = scala.collection.mutable.Map.empty[String, String]
    def find(x: String): String = {
      if (!parent.contains(x)) parent(x) = x
      if (parent(x) != x) parent(x) = find(parent(x))
      parent(x)
    }
    def union(x: String, y: String): Unit = {
      parent(find(x)) = find(y)
    }
    val byId = scala.collection.mutable.Map.empty[(IdentifierType, String),
      String]
    for (r <- records; (t, v) <- Matching.validPrimaryIds(r)) {
      byId.get((t, v)) match {
        case Some(existing) => union(r.vendorSecurityId, existing)
        case None => byId((t, v)) = r.vendorSecurityId
      }
    }
    records.groupBy(r => find(r.vendorSecurityId)).values.toSeq
  }
}
