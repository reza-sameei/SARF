// in the name of ALLAH

organization := "com.bisphone"

name := "sarf" // Simple Abstraction for Remote Function

version := "0.8.4"

scalaVersion := "2.12.6"

crossScalaVersions := Seq("2.11.12", "2.12.6"/*, "2.13.0-M4"*/)

scalacOptions ++= Seq("-feature", "-deprecation", "-language:postfixOps")

def akka (
    module : String,
    version: String = "2.5.17"
) = "com.typesafe.akka" %% module % version

fork := true

libraryDependencies ++= Seq(
    "com.bisphone" %% "akkastream" % "0.4.4",
    "com.bisphone" %% "std" % "0.13.0",
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "ch.qos.logback" % "logback-classic" % "1.1.7" % Provided
)

libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.5" % Test,
    "com.bisphone" %% "testkit" % "0.4.2" % Test,
    akka("akka-testkit") % Test,
    akka("akka-stream-testkit") % Test
)
