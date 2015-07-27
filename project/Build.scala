import sbt._
import sbt.Keys._

object AkkaGPSdBuild extends Build {

  val requestedScalaVersion = System.getProperty("akka.gpsd.build.scalaVersion", "2.11.7")

  lazy val buildSettings = Seq(
    organization := "cuenen.raymond.akka.gpsd",
    version      := "1.0.0-SNAPSHOT",
    scalaVersion := requestedScalaVersion
  )

  lazy val akkaGPSd = Project(
    id = "akka-gpsd",
    base = file("."),
    settings = buildSettings ++ Seq(libraryDependencies ++= Dependencies.akkaGPSd)
  )
}

object Dependencies {

  object Versions {
    val akkaVersion = System.getProperty("akka.gpsd.build.akkaVersion", "2.3.12")
  }

  object Compile {
    import Versions._

    val akkaActor = "com.typesafe.akka" %% "akka-actor" % akkaVersion

    object Test {
      val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion      % "test"
    }

  }

  import Compile._

  val akkaGPSd = Seq(akkaActor, Test.akkaTestKit)

}

