ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.backoffice"

lazy val root = (project in file("."))
  .settings(
    name := "corporate-actions-processor",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "4.3.0" % "provided",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    )
  )
