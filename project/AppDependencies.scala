import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  lazy val bootstrapVersion = "7.12.0"
  lazy val hmrcMongoVersion = "0.74.0"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-backend-play-28"          % bootstrapVersion,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-play-28"                 % hmrcMongoVersion,
    "com.beachape"                %% "enumeratum-play-json"               % "1.6.0",
    "org.typelevel"               %% "cats-core"                          % "2.1.1",
    "commons-codec"               %  "commons-codec"                      % "1.15"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-test-play-28"             % bootstrapVersion,
    "org.mockito"                 %% "mockito-scala-scalatest"            % "1.16.42",
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-test-play-28"            % hmrcMongoVersion,
    "com.github.tomakehurst"      %  "wiremock-jre8-standalone"           % "2.27.1")
    .map(_ % "test")
}
