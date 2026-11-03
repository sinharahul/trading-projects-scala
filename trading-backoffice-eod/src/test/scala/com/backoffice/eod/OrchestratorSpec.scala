package com.backoffice.eod

import com.backoffice.eod.jobs._
import com.backoffice.eod.model._
import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec

class OrchestratorSpec extends AnyFlatSpec {
  class StubJob(val name: String, deps: Seq[String] = Seq.empty,
    failTransiently: Int = 0) extends EodJob {
    override def dependencies: Seq[String] = deps
    var attempts = 0
    override def run(businessDate: LocalDate): Long = {
      attempts += 1
      if (attempts <= failTransiently)
        throw new TransientFailure("simulated blip")
      42L
    }
  }

  "The orchestrator" should "run jobs in dependency order" in {
    val order = scala.collection.mutable.ListBuffer.empty[String]
    class TrackingJob(n: String, deps: Seq[String]) extends EodJob {
      override def name: String = n
      override def dependencies: Seq[String] = deps
      override def run(d: LocalDate): Long = { order += n; 1L }
    }
    val jobs = Seq(
      new TrackingJob("c", Seq("b")),
      new TrackingJob("a", Seq.empty),
      new TrackingJob("b", Seq("a")))
    val history = new RunHistoryStub
    val orch = new Orchestrator(jobs, history)
    assert(orch.orderedJobs.map(_.name) == Seq("a", "b", "c"))
    assert(orch.runDag(LocalDate.of(2026, 10, 30)))
    assert(order.toSeq == Seq("a", "b", "c"))
  }

  it should "retry transient failures and then succeed" in {
    val job = new StubJob("flaky", failTransiently = 2)
    val orch = new Orchestrator(Seq(job), new RunHistoryStub)
    assert(orch.runDag(LocalDate.of(2026, 10, 30)))
    assert(job.attempts == 3)
  }

  it should "stop the DAG on permanent failure" in {
    class BadJob extends EodJob {
      override def name: String = "bad"
      override def run(d: LocalDate): Long =
        throw new PermanentFailure("gate failed")
    }
    var downstreamRan = false
    class DownJob extends EodJob {
      override def name: String = "down"
      override def dependencies: Seq[String] = Seq("bad")
      override def run(d: LocalDate): Long = { downstreamRan = true; 1L }
    }
    val orch = new Orchestrator(Seq(new BadJob, new DownJob), new RunHistoryStub)
    assert(!orch.runDag(LocalDate.of(2026, 10, 30)))
    assert(!downstreamRan)
  }

  it should "reject dependency cycles at startup" in {
    val a = new StubJob("a", Seq("b"))
    val b = new StubJob("b", Seq("a"))
    val orch = new Orchestrator(Seq(a, b), new RunHistoryStub)
    assertThrows[PermanentFailure] { orch.orderedJobs }
  }
}
