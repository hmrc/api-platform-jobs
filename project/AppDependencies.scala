import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  lazy val bootstrapVersion = "8.4.0"
  lazy val hmrcMongoVersion = "1.7.0"
  val apiDomainVersion = "0.11.0"
  val commonDomainVersion = "0.11.0"
  val appDomainVersion = "0.34.0"
  
  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-backend-play-30"          % bootstrapVersion,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-play-30"                 % hmrcMongoVersion,
    "org.typelevel"               %% "cats-core"                          % "2.10.0",
    "commons-codec"               %  "commons-codec"                      % "1.15",
    "uk.gov.hmrc"                 %% "api-platform-application-domain"    % appDomainVersion
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-30"           % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-test-play-30"          % hmrcMongoVersion,
    "uk.gov.hmrc"             %% "api-platform-test-common-domain"  % commonDomainVersion
  )
  .map(_ % "test")
}
