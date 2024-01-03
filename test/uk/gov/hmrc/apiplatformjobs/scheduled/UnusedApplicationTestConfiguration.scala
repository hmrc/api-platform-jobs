/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apiplatformjobs.scheduled

import java.time.{LocalDate, ZoneOffset}
import scala.util.Random

import com.typesafe.config.ConfigFactory

import play.api.Configuration

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

import uk.gov.hmrc.apiplatformjobs.models.{Environment, UnusedApplication}

trait UnusedApplicationTestConfiguration extends FixedClock {

  def defaultConfiguration: Configuration = jobConfiguration()

  // scalastyle:off magic.number
  def jobConfiguration(
      deleteUnusedSandboxApplicationsAfter: Int = 365,
      deleteUnusedProductionApplicationsAfter: Int = 365,
      notifyDeletionPendingInAdvanceForSandbox: Seq[Int] = Seq(30),
      notifyDeletionPendingInAdvanceForProduction: Seq[Int] = Seq(30),
      sandboxEnvironmentName: String = "Sandbox",
      productionEnvironmentName: String = "Production"
    ): Configuration = {
    val sandboxNotificationsString    = notifyDeletionPendingInAdvanceForSandbox.mkString("", "d,", "d")
    val productionNotificationsString = notifyDeletionPendingInAdvanceForProduction.mkString("", "d,", "d")

    new Configuration(ConfigFactory.parseString(s"""
                                                   |UnusedApplications {
                                                   |  SANDBOX {
                                                   |    deleteUnusedApplicationsAfter = ${deleteUnusedSandboxApplicationsAfter}d
                                                   |    sendNotificationsInAdvance = [$sandboxNotificationsString]
                                                   |    environmentName = "$sandboxEnvironmentName"
                                                   |  }
                                                   |
                                                   |  PRODUCTION {
                                                   |    deleteUnusedApplicationsAfter = ${deleteUnusedProductionApplicationsAfter}d
                                                   |    sendNotificationsInAdvance = [$productionNotificationsString]
                                                   |    environmentName = "$productionEnvironmentName"
                                                   |  }
                                                   |}
                                                   |
                                                   |UpdateUnusedApplicationRecordsJob {
                                                   |  SANDBOX {
                                                   |    startTime = "00:30" # Time is in UTC
                                                   |    executionInterval = 1d
                                                   |    enabled = false
                                                   |  }
                                                   |
                                                   |  PRODUCTION {
                                                   |    startTime = "00:40" # Time is in UTC
                                                   |    executionInterval = 1d
                                                   |    enabled = false
                                                   |  }
                                                   |}
                                                   |
                                                   |SendUnusedApplicationNotificationsJob {
                                                   |  SANDBOX {
                                                   |    startTime = "00:50" # Time is in UTC
                                                   |    executionInterval = 1d
                                                   |    enabled = false
                                                   |  }
                                                   |
                                                   |  PRODUCTION {
                                                   |    startTime = "01:00" # Time is in UTC
                                                   |    executionInterval = 1d
                                                   |    enabled = false
                                                   |  }
                                                   |}
                                                   |
                                                   |DeleteUnusedApplicationsJob {
                                                   |  SANDBOX {
                                                   |    startTime = "01:10" # Time is in UTC
                                                   |    executionInterval = 1d
                                                   |    enabled = false
                                                   |  }
                                                   |
                                                   |  PRODUCTION {
                                                   |    startTime = "01:20" # Time is in UTC
                                                   |    executionInterval = 1d
                                                   |    enabled = false
                                                   |  }
                                                   |}
                                                   |""".stripMargin))
  }

  def unusedApplicationRecord(applicationId: ApplicationId, environment: Environment): UnusedApplication =
    UnusedApplication(
      applicationId,
      Random.alphanumeric.take(10).mkString, // scalastyle:off magic.number
      Seq.empty,
      environment,
      LocalDate.now.minusYears(1),
      List.empty,
      LocalDate.now(ZoneOffset.UTC)
    )
}
