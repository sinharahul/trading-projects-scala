// build.sbt
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.trading"

lazy val root = (project in file("."))
  .settings(
    name := "regulatory-reporting",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    )
  )
