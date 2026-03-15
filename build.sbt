val scala213 = "2.13.18"
val scala3   = "3.8.1"

val zioVersion            = "2.1.16"
val zioBlocksVersion      = "0.0.29"
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
    scalacOptions ++= Seq("-deprecation", "-feature"),
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, _)) => Seq("-Xsource:3")
        case _            => Seq.empty
      }
    }
  )
)

lazy val root = (project in file("."))
  .aggregate(zioNats, zioNatsTestkit, zioNatsTest, zioNatsExamples)
  .settings(
    name               := "zio-nats-root",
    publish / skip     := true,
    crossScalaVersions := Nil
  )

lazy val zioNats = (project in file("zio-nats"))
  .settings(
    name := "zio-nats",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"                    % zioVersion,
      "dev.zio" %% "zio-streams"            % zioVersion,
      "dev.zio" %% "zio-blocks-schema"      % zioBlocksVersion,
      "io.nats"  % "jnats"                 % jnatsVersion
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
    ),
    // Podman/Docker socket configuration for testcontainers.
    // Forking is required so env vars are passed to the test JVM.
    // parallelExecution = false ensures containers don't race each other.
    Test / fork               := true,
    Test / parallelExecution  := false,
    Test / envVars ++= {
      val podmanSocket = "/tmp/podman/podman-machine-default-api.sock"
      val dockerSocket = "/var/run/docker.sock"
      val socketPath =
        if (java.nio.file.Files.exists(java.nio.file.Paths.get(podmanSocket))) podmanSocket
        else dockerSocket
      Map(
        "DOCKER_HOST"                  -> s"unix://$socketPath",
        "TESTCONTAINERS_RYUK_DISABLED" -> "true",
        "TESTCONTAINERS_HOST_OVERRIDE" -> "localhost"
      )
    }
  )

lazy val zioNatsExamples = (project in file("examples"))
  .dependsOn(zioNats)
  .settings(
    name           := "zio-nats-examples",
    publish / skip := true
  )
