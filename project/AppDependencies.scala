import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  private lazy val bootstrapVersion = "9.3.0"
  private lazy val hmrcMongoVersion = "1.7.0"
  private lazy val appDomainVersion = "0.61.12"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-backend-play-30"                  % bootstrapVersion,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-play-30"                         % hmrcMongoVersion,
    "org.typelevel"               %% "cats-core"                                  % "2.10.0",
    "commons-codec"               %  "commons-codec"                              % "1.16.0",
    "uk.gov.hmrc"                 %% "api-platform-application-domain"            % appDomainVersion,
    "com.github.pureconfig"       %% "pureconfig"                                 % "0.17.7"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-test-play-30"                     % bootstrapVersion,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-test-play-30"                    % hmrcMongoVersion,
    "uk.gov.hmrc"                 %% "api-platform-application-domain-fixtures"   % appDomainVersion
  )
  .map(_ % "test")
}
