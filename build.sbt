val scala3 = "3.3.7"

val zioVersion            = "2.1.24"
val zioBlocksVersion      = "0.0.29"
val jnatsVersion          = "2.25.2"
val testcontainersVersion = "0.44.1"
val jsoniterScalaVersion  = "2.38.9"
val playJsonVersion       = "3.0.4"

inThisBuild(
  List(
    organization       := "io.github.pietersp",
    homepage           := Some(url("https://github.com/pietersp/zio-nats")),
    licenses           := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion       := scala3,
    crossScalaVersions := Seq(scala3),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= Seq("-deprecation", "-feature"),
    developers := List(
      Developer("pietersp", "Pieter", "", url("https://github.com/pietersp"))
    ),
    scmInfo := Some(
      ScmInfo(url("https://github.com/pietersp/zio-nats"), "scm:git@github.com:pietersp/zio-nats.git")
    ),
    publishTo := {
      val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
      else localStaging.value
    },
    pomIncludeRepository := { _ => false },
    publishMavenStyle    := true
  )
)

lazy val root = (project in file("."))
  .aggregate(zioNatsCore, zioNatsZioBlocks, zioNats, zioNatsJsoniter, zioNatsPlayJson, zioNatsTestkit, zioNatsTest, zioNatsExamples, docs)
  .settings(
    name               := "zio-nats-root",
    publish / skip     := true,
    crossScalaVersions := Nil,
    mimaPreviousArtifacts := Set.empty
  )

lazy val zioNatsCore = (project in file("zio-nats-core"))
  .enablePlugins(ScoverageSbtPlugin, MimaPlugin)
  .settings(
    name := "zio-nats-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "io.nats"  % "jnats"       % jnatsVersion
    ),
    mimaPreviousArtifacts := Set.empty
  )

lazy val zioNatsZioBlocks = (project in file("zio-nats-zio-blocks"))
  .enablePlugins(ScoverageSbtPlugin, MimaPlugin)
  .dependsOn(zioNatsCore)
  .settings(
    name := "zio-nats-zio-blocks",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-blocks-schema" % zioBlocksVersion
    ),
    mimaPreviousArtifacts := Set.empty
  )

lazy val zioNats = (project in file("zio-nats"))
  .dependsOn(zioNatsCore, zioNatsZioBlocks)
  .settings(
    name                      := "zio-nats",
    Compile / sources         := Seq.empty,
    Compile / resources       := Seq.empty,
    mimaPreviousArtifacts     := Set.empty
  )

lazy val zioNatsJsoniter = (project in file("zio-nats-jsoniter"))
  .dependsOn(zioNatsCore)
  .enablePlugins(MimaPlugin)
  .settings(
    name := "zio-nats-jsoniter",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterScalaVersion
    ),
    mimaPreviousArtifacts := Set.empty
  )

lazy val zioNatsPlayJson = (project in file("zio-nats-play-json"))
  .dependsOn(zioNatsCore)
  .enablePlugins(MimaPlugin)
  .settings(
    name := "zio-nats-play-json",
    libraryDependencies ++= Seq(
      "org.playframework" %% "play-json" % playJsonVersion
    ),
    mimaPreviousArtifacts := Set.empty
  )

lazy val zioNatsTestkit = (project in file("zio-nats-testkit"))
  .dependsOn(zioNatsCore)
  .enablePlugins(MimaPlugin)
  .settings(
    name := "zio-nats-testkit",
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio-test"                  % zioVersion,
      "com.dimafeng" %% "testcontainers-scala-core" % testcontainersVersion
    ),
    mimaPreviousArtifacts := Set.empty
  )

lazy val zioNatsTest = (project in file("zio-nats-test"))
  .dependsOn(zioNatsCore, zioNatsZioBlocks, zioNatsJsoniter, zioNatsPlayJson, zioNatsTestkit)
  .settings(
    name           := "zio-nats-test",
    publish / skip := true,
    mimaPreviousArtifacts := Set.empty,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"                                          % zioVersion              % Test,
      "dev.zio" %% "zio-test-sbt"                                      % zioVersion              % Test,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion % Test,
      "org.playframework"                     %% "play-json"             % playJsonVersion       % Test
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

lazy val docs = project
  .in(file("zio-nats-docs"))
  .dependsOn(zioNatsCore, zioNatsZioBlocks, zioNatsJsoniter, zioNatsPlayJson)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
  .settings(
    name           := "zio-nats-docs",
    publish / skip := true,
    mimaPreviousArtifacts := Set.empty,
    mdocIn  := (LocalRootProject / baseDirectory).value / "docs",
    mdocOut := (LocalRootProject / baseDirectory).value / "website" / "docs",
    mdocVariables := Map("VERSION" -> version.value),
    ScalaUnidoc / unidoc / target :=
      (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
    ScalaUnidoc / unidoc / unidocProjectFilter :=
      inProjects(zioNatsCore, zioNatsZioBlocks, zioNatsJsoniter, zioNatsPlayJson),
    docusaurusCreateSite :=
      docusaurusCreateSite.dependsOn(Compile / unidoc).value,
    docusaurusPublishGhpages :=
      docusaurusPublishGhpages.dependsOn(Compile / unidoc).value
  )

lazy val zioNatsExamples = (project in file("examples"))
  .dependsOn(zioNatsCore, zioNatsZioBlocks)
  .settings(
    name           := "zio-nats-examples",
    publish / skip := true,
    mimaPreviousArtifacts := Set.empty
  )
