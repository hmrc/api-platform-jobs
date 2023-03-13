import scoverage.ScoverageKeys._

object ScoverageSettings {
  def apply() = Seq(
    coverageMinimum := 75,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    coverageExcludedPackages :=  Seq(
      "<empty>",
      "prod.*",
      "testOnly-DoNotUseInAppConf.*",
      "app.*",
      ".*Reverse.*",
      ".*Routes.*",
      "com.kenshoo.play.metrics.*",
      ".*definition.*",
      "uk.gov.hmrc.BuildInfo.*",
      "uk\\.gov\\.hmrc\\.apiplatform\\.modules\\.applications\\..*",
      "uk\\.gov\\.hmrc\\.apiplatform\\.modules\\.developers\\..*",
      "uk\\.gov\\.hmrc\\.apiplatform\\.modules\\.common\\..*"
    ).mkString(";")
  )
}
