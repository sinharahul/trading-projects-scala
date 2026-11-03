name := "trading-backoffice-eod"
version := "1.0.0"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "4.3.0",
  "org.apache.spark" %% "spark-sql" % "4.3.0",
  // Delta Lake 4.x (check Delta Lake's compatibility table for the exact patch for Spark 4.3.0).
  // Substitute the exact patch from that table for the placeholder below; no specific
  // Delta patch is claimed here as certified for Spark 4.3.0.
  "io.delta" %% "delta-spark" % "4.x",
  "org.postgresql" % "postgresql" % "42.7.3",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test
)

fork := true
javaOptions ++= Seq(
  "-Dspark.master=local[*]",
  "-Dio.netty.tryReflectionSetAccessible=true"
)
