name := "discovery-personalisation-candidate-test"

scalaVersion := "2.13.7"

val Http4sVersion = "0.23.18"
val CirceVersion = "0.14.0-M5"

// Build for the api server
lazy val api_server = project.in(file("api_server")).settings(
    name := "api_server",
    libraryDependencies ++= Seq(
      "org.http4s"      %% "http4s-circe"        % Http4sVersion,
      "org.http4s"      %% "http4s-dsl"          % Http4sVersion,
      "io.circe"        %% "circe-generic"       % CirceVersion,
      "io.circe" %% "circe-core" % CirceVersion,
      "io.circe" %% "circe-parser" % CirceVersion,

      "org.http4s" %% "http4s-dsl" % Http4sVersion,

      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion,

      "org.scalatest" %% "scalatest" % "3.2.15" % "test",
      "org.scalactic" %% "scalactic" % "3.2.15",

      "org.scalamock" %% "scalamock" % "5.1.0" % Test,

      "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime
    )
).dependsOn(spark_recommender_job)

lazy val compilerOptions = Seq(
  "-unchecked",
  "-feature",
  "-deprecation"
)

// Build for the Spark recommender
lazy val spark_recommender_job = project.in(file("spark_recommender_job")).settings(
    name := "spark_recommender_job",
    libraryDependencies ++= Seq(
        "org.apache.spark" %% "spark-core" % "3.0.0",
        "org.apache.spark" %% "spark-sql" % "3.0.0",
        "org.apache.spark" %% "spark-mllib" % "3.0.0",
    )
)

lazy val root = project.in(file(".")).settings(
    name := "recommender-project"
).settings(
    scalacOptions ++= compilerOptions,
    javaOptions ++= Seq("-Xms512M", "-Xmx2048M", "-XX:+CMSClassUnloadingEnabled"),
    addCommandAlias("build_recs", "spark_recommender_job/run"),
    addCommandAlias("run_server", "api_server/run")
).aggregate(spark_recommender_job, api_server)
