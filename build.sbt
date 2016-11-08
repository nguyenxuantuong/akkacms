name := "akka-cms"

version := "1.0"

scalaVersion := "2.11.8"

ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }