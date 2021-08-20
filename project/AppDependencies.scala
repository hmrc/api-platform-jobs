import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps("test")

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-play-26"          % "4.0.0",
    "uk.gov.hmrc"                 %% "mongo-lock"                 % "6.23.0-play-26",
    "uk.gov.hmrc"                 %% "simple-reactivemongo"       % "7.30.0-play-26",
    "uk.gov.hmrc"                 %% "play-scheduling"            % "7.4.0-play-26",
    "com.typesafe.play"           %  "play-json-joda_2.12"        % "2.6.0",
    "com.beachape"                %% "enumeratum-play-json"       % "1.6.0",
    "org.typelevel"               %% "cats-core"                  % "2.1.1"
  )

  private def testDeps(scope: String) = Seq(
    "org.scalatestplus.play"      %% "scalatestplus-play"         % "3.1.3"           % scope,
    "org.mockito"                 %% "mockito-scala-scalatest"    % "1.7.1"           % scope,
    "uk.gov.hmrc"                 %% "reactivemongo-test"         % "4.21.0-play-26"  % scope,
    "com.github.tomakehurst"      %  "wiremock-jre8-standalone"   % "2.27.1"          % scope
  )
}
