ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.backoffice"

lazy val root = (project in file("."))
  .settings(
    name := "trade-capture",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % "4.3.0",
      "org.apache.spark" %% "spark-sql" % "4.3.0",
      // Delta Lake 4.x: replace 4.x with the exact patch from Delta Lake's compatibility table for Spark 4.3.0.
      "io.delta" %% "delta-spark" % "4.x",
      "org.apache.kafka" % "kafka-clients" % "3.6.1",
      "org.postgresql" % "postgresql" % "42.7.1",
      "com.typesafe" % "config" % "1.4.3",
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    // Spark pulls in an older Jackson; force a consistent version.
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.15.2"
    )
  )
