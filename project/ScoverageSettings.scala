import scoverage.ScoverageKeys.*

object ScoverageSettings {
  def apply() = Seq(
    coverageMinimumStmtTotal := 88.0,
    coverageMinimumBranchTotal := 86.0,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    coverageExcludedPackages :=  Seq(
      "<empty>",
      "prod.*",
      "testOnly-DoNotUseInAppConf.*",
      ".*Reverse.*",
      ".*Routes.*",
      ".*definition.*",
      "uk.gov.hmrc.BuildInfo.*"
    ).mkString(";")
  )
}
