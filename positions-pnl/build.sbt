// build.sbt (project3 excerpt)
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "positions-pnl",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "4.3.0",
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "4.3.0",
      // Delta Lake 4.x (check Delta Lake's compatibility table for the exact patch for Spark 4.3.0)
      // Substitute that exact patch version below; do not guess one.
      "io.delta" %% "delta-spark" % "4.x",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    )
  )
