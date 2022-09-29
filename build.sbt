ThisBuild / scalaVersion := "3.2.0"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "pl.mlynik"

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")

lazy val root = (project in file("."))
  .settings(
    name := "zio-journal"
  )
  .aggregate(core, `postgres-journal`, `examples`)

lazy val `core` = (project in file("core"))
  .settings(
    name := "core"
  )
  .settings(commonSettings)

lazy val `postgres-journal` = (project in file("postgres-journal"))
  .settings(
    name := "postgres-journal",
    libraryDependencies ++= Seq(
      "org.postgresql"         % "postgresql"                        % "42.5.0",
      "io.getquill"           %% "quill-jdbc-zio"                    % "4.4.0",
      "org.flywaydb"           % "flyway-core"                       % "9.3.0",
      "io.github.scottweaver" %% "zio-2-0-testcontainers-postgresql" % "0.8.0"
    )
  )
  .dependsOn(`core`)
  .settings(commonSettings)

lazy val `examples` = (project in file("examples"))
  .settings(
    name := "examples",
    libraryDependencies ++= Seq(
      "com.devsisters" %% "shardcake-manager"       % "2.0.1",
      "com.devsisters" %% "shardcake-protocol-grpc" % "2.0.1"
    )
  )
  .dependsOn(`core`)
  .settings(commonSettings)

lazy val commonSettings = Def.settings(
  resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
  testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")),
  Test / fork    := true,
  libraryDependencies ++= Seq(
    "dev.zio"                       %% "zio"                           % "2.0.2",
    "dev.zio"                       %% "zio-concurrent"                % "2.0.2",
    "dev.zio"                       %% "zio-streams"                   % "2.0.2",
    "dev.zio"                       %% "zio-logging"                   % "2.1.1",
    "dev.zio"                       %% "zio-json"                      % "0.3.0",
    "com.softwaremill.sttp.client3" %% "zio"                           % "3.8.0",
    "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.8.0",
    "dev.zio"                       %% "zio-test"                      % "2.0.2" % Test,
    "dev.zio"                       %% "zio-test-sbt"                  % "2.0.2" % Test,
    "dev.zio"                       %% "zio-test-magnolia"             % "2.0.2" % Test
  ),
  Test / fork    := true,
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:higherKinds",
    "-language:existentials",
    "-unchecked",
    "-Xfatal-warnings",
    "-language:postfixOps",
    "-Xprint-suspension"
  )
)
