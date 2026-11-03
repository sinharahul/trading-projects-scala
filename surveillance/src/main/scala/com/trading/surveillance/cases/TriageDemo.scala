package com.trading.surveillance.cases

import org.apache.spark.sql.SparkSession

object TriageDemo {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("triage-demo")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .getOrCreate()

    val base = "data/delta/surveillance"
    val manager = new CaseManager(
      spark, s"$base/cases", s"$base/notes", s"$base/audit")

    // 1. A wash-trade alert arrives in the queue. Open a case for it.
    val caseId = manager.openCase(
      alertIds = Seq("alert-wash-001"),
      detector = "WASH_TRADE",
      symbol = "ACME",
      accountIds = Seq("ACC-201", "ACC-202"),
      ownerIds = Seq("OWN-201")
    )
    println(s"Opened case $caseId")

    // 2. Assign to an analyst and record the first review.
    manager.assign(caseId, "analyst.jane")
    manager.addNote(caseId, "analyst.jane",
      "Reviewed 7 trades between ACC-201 and ACC-202, same owner OWN-201, " +
      "all within 4 minutes. Accounts share a device fingerprint. " +
      "No client-cross documentation on file.")

    // 3. Second review: check the P&L and the account history.
    manager.addNote(caseId, "analyst.jane",
      "P&L: no economic gain from the wash trades themselves; volume " +
      "inflated ACME daily volume by ~2%. Prior alert for OWN-201 in August " +
      "was closed as false positive (error account). This pattern is " +
      "larger and repeated — escalating.")

    // 4. Close with a disposition.
    manager.close(caseId, "analyst.jane", "ESCALATED")
    println("Case escalated. Audit trail:")
    spark.read.format("delta").load(s"$base/audit")
      .filter(s"caseId = '$caseId'")
      .orderBy("createdAt")
      .select("eventType", "analyst", "detail")
      .show(truncate = false)
  }
}
