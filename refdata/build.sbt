name := "refdata"
version := "0.1.0"
scalaVersion := "2.13.12"

// Delta Lake 4.x (check Delta Lake's compatibility table for the exact patch for Spark 4.3.0).
// Fill deltaVersion with that exact patch before building; "4.x" below is a
// placeholder, not a resolvable version, and sbt will fail to resolve it until replaced.
val deltaVersion = "4.x" // <-- REPLACE with the exact Delta 4.x patch from Delta Lake's compatibility table

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql" % "4.3.0",
  "io.delta" %% "delta-spark" % deltaVersion,
  "com.lihaoyi" %% "ujson" % "3.1.3",
  "org.postgresql" % "postgresql" % "42.7.3",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)

fork := true
javaOptions ++= Seq(
  "-Xmx4g",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED"
)
