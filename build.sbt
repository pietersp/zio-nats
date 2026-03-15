val scala213 = "2.13.16"
val scala3   = "3.3.5"

val zioVersion            = "2.1.16"
val jnatsVersion          = "2.25.2"
val testcontainersVersion = "0.41.8"

inThisBuild(
  List(
    organization       := "dev.zio",
    homepage           := Some(url("https://github.com/your-org/zio-nats")),
    licenses           := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion       := scala213,
    crossScalaVersions := Seq(scala213, scala3),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, _)) => Seq("-Xsource:3", "-deprecation", "-feature")
        case _            => Seq("-deprecation", "-feature")
      }
    }
  )
)

lazy val root = (project in file("."))
  .aggregate(zioNats, zioNatsTestkit, zioNatsTest)
  .settings(
    name               := "zio-nats-root",
    publish / skip     := true,
    crossScalaVersions := Nil
  )

lazy val zioNats = (project in file("zio-nats"))
  .settings(
    name := "zio-nats",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "io.nats"  % "jnats"       % jnatsVersion
    )
  )

lazy val zioNatsTestkit = (project in file("zio-nats-testkit"))
  .dependsOn(zioNats)
  .settings(
    name := "zio-nats-testkit",
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio-test"                  % zioVersion,
      "com.dimafeng" %% "testcontainers-scala-core" % testcontainersVersion
    )
  )

lazy val zioNatsTest = (project in file("zio-nats-test"))
  .dependsOn(zioNats, zioNatsTestkit)
  .settings(
    name           := "zio-nats-test",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    )
  )
