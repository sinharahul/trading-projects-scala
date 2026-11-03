// build.sbt
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.backoffice"

lazy val root = (project in file("."))
  .settings(
    name := "risk-margin-engine",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "com.typesafe" % "config" % "1.4.3"
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint")
  )
