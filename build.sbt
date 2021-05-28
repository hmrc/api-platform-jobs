import play.core.PlayVersion
import sbt.Tests.{Group, SubProcess}
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin._
import uk.gov.hmrc.SbtAutoBuildPlugin

import bloop.integrations.sbt.BloopDefaults

lazy val root = (project in file("."))
  .settings(
    name := "api-platform-jobs",
    organization := "uk.gov.hmrc",
    scalaVersion := "2.12.12",
    scalacOptions += "-Ypartial-unification",
    majorVersion := 0,
    PlayKeys.playDefaultPort := 6700,
    resolvers ++= Seq(
      Resolver.typesafeRepo("releases")
    ),
    libraryDependencies ++= AppDependencies(),
    publishingSettings,
  )
  .settings(ScoverageSettings())
  .settings(
    inConfig(Test)(BloopDefaults.configSettings),
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-eT"),
    Test / fork := false,
    Test / parallelExecution := false,
    Test / unmanagedResourceDirectories += baseDirectory.value / "test" / "resources"
  )
  .enablePlugins(PlayScala, SbtAutoBuildPlugin, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)

def oneForkedJvmPerTest(tests: Seq[TestDefinition]) = {
  tests.map { test =>
    new Group(test.name, Seq(test), SubProcess(ForkOptions().withRunJVMOptions(Vector(s"-Dtest.name=${test.name}"))))
  }
}
