val scala3 = "3.3.7"

val zioVersion            = "2.1.24"
val zioBlocksVersion      = "0.0.29"
val jnatsVersion          = "2.25.2"
val testcontainersVersion = "0.44.1"
val jsoniterScalaVersion  = "2.38.9"

inThisBuild(
  List(
    organization       := "dev.zio",
    homepage           := Some(url("https://github.com/pietersp/zio-nats")),
    licenses           := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion       := scala3,
    crossScalaVersions := Seq(scala3),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
)

lazy val root = (project in file("."))
  .aggregate(zioNatsCore, zioNatsZioBlocks, zioNats, zioNatsJsoniter, zioNatsTestkit, zioNatsTest, zioNatsExamples)
  .settings(
    name               := "zio-nats-root",
    publish / skip     := true,
    crossScalaVersions := Nil
  )

lazy val zioNatsCore = (project in file("zio-nats-core"))
  .enablePlugins(ScoverageSbtPlugin)
  .settings(
    name := "zio-nats-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "io.nats"  % "jnats"       % jnatsVersion
    )
  )

lazy val zioNatsZioBlocks = (project in file("zio-nats-zio-blocks"))
  .enablePlugins(ScoverageSbtPlugin)
  .dependsOn(zioNatsCore)
  .settings(
    name := "zio-nats-zio-blocks",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-blocks-schema" % zioBlocksVersion
    )
  )

lazy val zioNats = (project in file("zio-nats"))
  .dependsOn(zioNatsCore, zioNatsZioBlocks)
  .settings(
    name                      := "zio-nats",
    Compile / sources         := Seq.empty,
    Compile / resources       := Seq.empty
  )

lazy val zioNatsJsoniter = (project in file("zio-nats-jsoniter"))
  .dependsOn(zioNatsCore)
  .settings(
    name := "zio-nats-jsoniter",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterScalaVersion
    )
  )

lazy val zioNatsTestkit = (project in file("zio-nats-testkit"))
  .dependsOn(zioNatsCore)
  .settings(
    name := "zio-nats-testkit",
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio-test"                  % zioVersion,
      "com.dimafeng" %% "testcontainers-scala-core" % testcontainersVersion
    )
  )

lazy val zioNatsTest = (project in file("zio-nats-test"))
  .dependsOn(zioNatsCore, zioNatsZioBlocks, zioNatsJsoniter, zioNatsTestkit)
  .settings(
    name           := "zio-nats-test",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"                                          % zioVersion              % Test,
      "dev.zio" %% "zio-test-sbt"                                      % zioVersion              % Test,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion % Test
    ),
    // Podman/Docker socket configuration for testcontainers.
    // Forking is required so env vars are passed to the test JVM.
    // parallelExecution = false ensures containers don't race each other.
    Test / fork              := true,
    Test / parallelExecution := false,
    Test / envVars ++= {
      val podmanSocket = "/tmp/podman/podman-machine-default-api.sock"
      val dockerSocket = "/var/run/docker.sock"
      val socketPath   =
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
  .dependsOn(zioNatsCore, zioNatsZioBlocks)
  .settings(
    name           := "zio-nats-examples",
    publish / skip := true
  )
