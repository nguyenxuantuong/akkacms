import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.PathList


object Build extends Build {
  val mergeStrategy = assemblyMergeStrategy in assembly := {
    case PathList("org", "apache", "spark", "unused", "UnusedStubClass.class") => MergeStrategy.discard
    case PathList(ps@_*) if ps.last endsWith "pom.properties" => MergeStrategy.discard
    case PathList(ps@_*) if ps.last endsWith "pom.xml" => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }

  val akkaVersion = "2.4.11"
  val kafkaVersion = "0.10.0.1"
  val httpVersion = "3.0.0-RC1"

  val kafkaClients = "org.apache.kafka" % "kafka-clients" % kafkaVersion
  val algeBirdCore = "com.twitter" %% "algebird-core" % "0.12.0"

  val commonDependencies = Seq(
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
  )

  val json4sJackson = "org.json4s" %% "json4s-jackson" % "3.2.11"
  val json4sExt = "org.json4s" %% "json4s-ext" % "3.2.11"

  val coreDependencies = Seq(
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    kafkaClients,
    json4sJackson,
    json4sExt,
    algeBirdCore,
    "com.typesafe.akka" %% "akka-http-core" % httpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % httpVersion,
    "org.scalatest" %% "scalatest" % "2.2.4" % "test",
    "org.reactivestreams" % "reactive-streams-tck" % "1.0.0" % "test",
    "com.novocode" % "junit-interface" % "0.11" % "test",
    "junit" % "junit" % "4.12" % "test",
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",
    "ch.qos.logback" % "logback-classic" % "1.1.3" % "test",
    "org.slf4j" % "log4j-over-slf4j" % "1.7.12" % "test",
    "org.mockito" % "mockito-core" % "1.10.19" % "test",
    "net.manub" %% "scalatest-embedded-kafka" % "0.7.1" % "test",
    "com.clearspring.analytics" % "stream" % "2.9.5"
  )

  val projectDependencies = commonDependencies ++ coreDependencies ++ Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-agent" % akkaVersion,
    "com.typesafe.akka" % "akka-stream_2.11" % "2.4.8",
    "com.hootsuite" %% "akka-persistence-redis" % "0.3.0",
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    "net.databinder.dispatch" %% "dispatch-json4s-native" % "0.11.2",
    "com.google.protobuf" % "protobuf-java" % "2.5.0",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.4.2",
    "org.scalaz" %% "scalaz-core" % "7.1.0"
  )


  private val buildSettings = Defaults.coreDefaultSettings ++ Seq(
    version := "1.0",
    organization := "com.symantec.cloud",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-target:jvm-1.7"),
    scalaVersion := "2.11.8",
    resolvers := RepositoryResolvers.allResolvers,
    libraryDependencies ++= projectDependencies,
    test in assembly := {}
  )

  lazy val root = Project(
    id = "root",
    base = file("."),
    settings = buildSettings
  )
}