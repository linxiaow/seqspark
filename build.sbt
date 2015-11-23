name := "seqa"

version := "1.0"

organization := "org.dizhang"

scalaVersion := "2.10.6"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies ++= Seq(
	"org.apache.spark" %% "spark-core" % "1.3.1" % "provided",
	"org.apache.spark" % "spark-sql_2.10" % "1.3.1",
	"com.typesafe" % "config" % "1.2.1",
	"it.unimi.dsi" % "fastutil" % "7.0.4",
	"org.slf4j" % "slf4j-log4j12" % "1.7.12",
	"org.scalanlp" %% "breeze" % "0.11.2",
	"org.scalanlp" %% "breeze-natives" % "0.11.2",
	"org.scalaz" %% "scalaz-core" % "7.1.5",
	"org.scalatest" % "scalatest_2.10" % "2.2.4" % "test"
)

assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)

assemblyJarName in assembly := "SeqA-1.0.jar"
