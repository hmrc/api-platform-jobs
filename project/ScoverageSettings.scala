import scoverage.ScoverageKeys.*

object ScoverageSettings {
  def apply() = Seq(
    coverageMinimumStmtTotal := 75.0,
    coverageMinimumBranchTotal := 60.0,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    coverageExcludedPackages :=  Seq(
      "<empty>",
      "prod.*",
      "testOnly-DoNotUseInAppConf.*",
      "app.*",
      ".*Reverse.*",
      ".*Routes.*",
      ".*definition.*",
      "uk.gov.hmrc.BuildInfo.*",
      "uk\\.gov\\.hmrc\\.apiplatform\\.modules\\.applications\\..*",
      "uk\\.gov\\.hmrc\\.apiplatform\\.modules\\.developers\\..*",
      "uk\\.gov\\.hmrc\\.apiplatform\\.modules\\.common\\..*"
    ).mkString(";")
  )
}
