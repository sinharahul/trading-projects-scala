name := "surveillance"
version := "0.1.0"
scalaVersion := "2.13.12"

val sparkVersion = "4.3.0"
// Delta Lake 4.x (check Delta Lake's compatibility table for the exact patch for Spark 4.3.0).
// Substitute the exact patch from that table for the placeholder below; no specific
// Delta patch is claimed here as certified for Spark 4.3.0.
val deltaVersion = "4.x"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
  "org.apache.spark" %% "spark-streaming" % sparkVersion,
  "io.delta" %% "delta-spark" % deltaVersion,
  "org.apache.kafka" % "kafka-clients" % "3.6.1",
  "com.typesafe" % "config" % "1.4.3",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

fork := true
javaOptions ++= Seq(
  "-Xmx4g",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED"
)
