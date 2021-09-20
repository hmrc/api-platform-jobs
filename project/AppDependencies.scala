import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-backend-play-28"          % "5.14.0",
    "uk.gov.hmrc"                 %% "mongo-lock"                         % "7.0.0-play-28",
    "uk.gov.hmrc"                 %% "simple-reactivemongo"               % "8.0.0-play-28",
    "com.typesafe.play"           %  "play-json-joda_2.12"                % "2.6.0",
    "com.beachape"                %% "enumeratum-play-json"               % "1.6.0",
    "org.typelevel"               %% "cats-core"                          % "2.1.1"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-test-play-28"             % "5.14.0",
    "org.mockito"                 %% "mockito-scala-scalatest"            % "1.16.42",
    "uk.gov.hmrc"                 %% "reactivemongo-test"                 % "5.0.0-play-28",
    "com.github.tomakehurst"      %  "wiremock-jre8-standalone"           % "2.27.1"          
  ).map(_ % "test")
}
