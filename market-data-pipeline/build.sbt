name := "market-data-pipeline"
version := "0.1.0"
scalaVersion := "2.13.14"

val sparkVersion = "4.3.0"
val deltaVersion = "4.x" // Replace 4.x with the exact patch from Delta Lake's compatibility table for Spark 4.3.0
val kafkaVersion = "3.7.0"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
  "io.delta" %% "delta-spark" % deltaVersion,
  "org.apache.kafka" % "kafka-clients" % kafkaVersion,
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)

Test / fork := true
Test / javaOptions ++= Seq(
  "-Xms512M",
  "-Xmx2G",
  "-Dspark.testing=true"
)

// Delta Lake requires these Spark SQL extensions at runtime.
Runtime / javaOptions ++= Seq(
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
)
