package uk.gov.hmrc.apiplatformjobs.scheduled

import com.typesafe.config.ConfigFactory
import play.api.Configuration

trait UnusedApplicationTestConfiguration {

  def defaultConfiguration: Configuration = jobConfiguration()

  // scalastyle:off magic.number
  def jobConfiguration(deleteUnusedSandboxApplicationsAfter: Int = 365,
                       deleteUnusedProductionApplicationsAfter: Int = 365,
                       notifyDeletionPendingInAdvanceForSandbox: Seq[Int] = Seq(30),
                       notifyDeletionPendingInAdvanceForProduction: Seq[Int] = Seq(30)): Configuration = {
    val sandboxNotificationsString = notifyDeletionPendingInAdvanceForSandbox.mkString("", "d,", "d")
    val productionNotificationsString = notifyDeletionPendingInAdvanceForProduction.mkString("", "d,", "d")

    new Configuration(
      ConfigFactory.parseString(
        s"""
           |UnusedApplications {
           |  SANDBOX {
           |    deleteUnusedApplicationsAfter = ${deleteUnusedSandboxApplicationsAfter}d
           |    sendNotificationsInAdvance = [$sandboxNotificationsString]
           |  }
           |
           |  PRODUCTION {
           |    deleteUnusedApplicationsAfter = ${deleteUnusedProductionApplicationsAfter}d
           |    sendNotificationsInAdvance = [$productionNotificationsString]
           |  }
           |}
           |
           |UpdateUnusedApplicationRecordsJob {
           |  SANDBOX {
           |    startTime = "00:30"
           |    executionInterval = 1d
           |    enabled = false
           |  }
           |
           |  PRODUCTION {
           |    startTime = "01:00"
           |    executionInterval = 1d
           |    enabled = false
           |  }
           |}
           |
           |DeleteUnusedApplicationsJob {
           |  SANDBOX {
           |    startTime = "01:30"
           |    executionInterval = 1d
           |    enabled = false
           |  }
           |
           |  PRODUCTION {
           |    startTime = "02:00"
           |    executionInterval = 1d
           |    enabled = false
           |  }
           |}
           |""".stripMargin))
  }

}
