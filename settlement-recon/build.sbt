// build.sbt
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.backoffice"

lazy val root = (project in file("."))
  .settings(
    name := "settlement-recon",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
