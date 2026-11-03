package com.backoffice.eod.jobs

import com.backoffice.eod.model._
import java.time.{LocalDate, LocalDateTime}
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** Minimal dependency-ordered orchestrator for the EOD batch.
  *
  * Sequential execution is deliberate: it makes failure traces
  * trivial to read, and the EOD window is generous relative to
  * the data volumes in this book. Parallel execution of
  * independent branches is Exercise 1.
  */
class Orchestrator(
  jobs: Seq[EodJob],
  history: RunHistory,
  alert: String => Unit = msg => println(s"ALERT: $msg")
) {
  private val byName: Map[String, EodJob] =
    jobs.map(j => j.name -> j).toMap

  /** Topologically sorts the jobs. Throws PermanentFailure on
    * unknown dependencies or cycles, at startup, before any
    * job runs. */
  def orderedJobs: Seq[EodJob] = {
    val visited = mutable.Set.empty[String]
    val visiting = mutable.Set.empty[String]
    val ordered = mutable.ListBuffer.empty[EodJob]

    def visit(name: String): Unit = {
      if (visiting.contains(name))
        throw new PermanentFailure(s"Dependency cycle detected at job '$name'")
      if (!visited.contains(name)) {
        val job = byName.getOrElse(name,
          throw new PermanentFailure(s"Unknown dependency job '$name'"))
        visiting += name
        job.dependencies.foreach(visit)
        visiting -= name
        visited += name
        ordered += job
      }
    }
    jobs.map(_.name).foreach(visit)
    ordered.toSeq
  }

  /** Runs the full DAG for a business date. Returns true if every
    * job succeeded; false if the DAG stopped at a failed job.
    * Downstream jobs never run after a failure. */
  def runDag(businessDate: LocalDate): Boolean = {
    val ordered = orderedJobs
    var failed = false
    var idx = 0
    while (idx < ordered.length && !failed) {
      val job = ordered(idx)
      val ok = runWithRetries(job, businessDate)
      if (!ok) {
        failed = true
        val remaining = ordered.drop(idx + 1).map(_.name).mkString(", ")
        alert(s"EOD $businessDate STOPPED at job '${job.name}'. " +
          s"Skipped downstream jobs: $remaining. " +
          s"Morning reports must be treated as stale.")
      }
      idx += 1
    }
    !failed
  }

  private def runWithRetries(job: EodJob, businessDate: LocalDate): Boolean = {
    var attempt = 0
    var lastError: Throwable = null
    var succeeded = false
    while (attempt <= job.maxRetries && !succeeded) {
      val started = LocalDateTime.now()
      Try(job.run(businessDate)) match {
        case Success(rows) =>
          history.record(businessDate, job.name, "SUCCEEDED", started,
            LocalDateTime.now(), rows, None)
          println(s"[${job.name}] SUCCEEDED, $rows rows, attempt ${attempt + 1}")
          succeeded = true
        case Failure(e: PermanentFailure) =>
          history.record(businessDate, job.name, "FAILED", started,
            LocalDateTime.now(), 0, Some(e.getMessage))
          println(s"[${job.name}] PERMANENT FAILURE: ${e.getMessage}")
          return false
        case Failure(e: TransientFailure) =>
          lastError = e
          attempt += 1
          if (attempt <= job.maxRetries) {
            val backoffMs = 1000L * math.pow(2, attempt - 1).toLong
            println(s"[${job.name}] transient failure, retry $attempt " +
              s"after ${backoffMs}ms: ${e.getMessage}")
            Thread.sleep(backoffMs)
          }
        case Failure(e) =>
          history.record(businessDate, job.name, "FAILED", started,
            LocalDateTime.now(), 0, Some(s"Unexpected: ${e.getMessage}"))
          println(s"[${job.name}] UNEXPECTED FAILURE: ${e.getMessage}")
          return false
      }
    }
    if (!succeeded && lastError != null) {
      history.record(businessDate, job.name, "FAILED", LocalDateTime.now(),
        LocalDateTime.now(), 0, Some(s"Retries exhausted: ${lastError.getMessage}"))
      println(s"[${job.name}] FAILED after ${job.maxRetries} retries")
    }
    succeeded
  }
}

package com.backoffice.eod.jobs

import java.time.{LocalDate, LocalDateTime}
import org.apache.spark.sql.SparkSession

/** Operational control table: one row per job execution.
  * This is the first thing the on-call engineer reads at 7 AM.
  */
class RunHistory(spark: SparkSession) {
  import spark.implicits._

  def ensureTable(): Unit = {
    spark.sql(
      """CREATE TABLE IF NOT EXISTS ops.eod_run_history (
        |  business_date DATE,
        |  job_name STRING,
        |  status STRING,
        |  started_at TIMESTAMP,
        |  ended_at TIMESTAMP,
        |  rows_processed BIGINT,
        |  error_text STRING
        |) USING DELTA PARTITIONED BY (business_date)""".stripMargin)
  }

  def record(businessDate: LocalDate, jobName: String, status: String,
    started: LocalDateTime, ended: LocalDateTime,
    rows: Long, error: Option[String]): Unit = {
    val df = Seq((businessDate.toString, jobName, status, started.toString,
      ended.toString, rows, error.orNull)).toDF(
      "business_date", "job_name", "status", "started_at", "ended_at",
      "rows_processed", "error_text")
    df.write.format("delta").mode("append").saveAsTable("ops.eod_run_history")
  }

  /** The morning status query: one row per job for the date. */
  def statusFor(businessDate: LocalDate): Unit = {
    spark.sql(
      s"""SELECT job_name, status, rows_processed, error_text
         |FROM ops.eod_run_history
         |WHERE business_date = DATE '$businessDate'
         |ORDER BY started_at""".stripMargin).show(false)
  }
}
