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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

import uk.gov.hmrc.apiplatformjobs.connectors.EmailConnector
import uk.gov.hmrc.apiplatformjobs.models.UnusedApplication
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.services.UnusedApplicationsService
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class SendUnusedApplicationNotificationsJobSpec extends AsyncHmrcSpec with UnusedApplicationTestConfiguration with FixedClock {

  trait Setup extends BaseSetup {
    val mockEmailConnector: EmailConnector                             = mock[EmailConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]
    val mockUnusedApplicationsService: UnusedApplicationsService       = mock[UnusedApplicationsService]
  }

  trait SandboxSetup extends Setup {
    val environment: Environment = Environment.SANDBOX
    val environmentName: String  = "Sandbox"

    val underTest =
      new SendUnusedSandboxApplicationNotificationsJob(
        mockUnusedApplicationsRepository,
        mockEmailConnector,
        mockUnusedApplicationsService,
        jobConfiguration(sandboxEnvironmentName = environmentName),
        FixedClock.clock,
        mockLockRepository
      )
  }

  trait ProductionSetup extends Setup {
    val environment: Environment = Environment.PRODUCTION
    val environmentName: String  = "Production"

    val underTest =
      new SendUnusedProductionApplicationNotificationsJob(
        mockUnusedApplicationsRepository,
        mockEmailConnector,
        mockUnusedApplicationsService,
        jobConfiguration(productionEnvironmentName = environmentName),
        FixedClock.clock,
        mockLockRepository
      )
  }

  "SANDBOX job" should {
    "send notifications for applications that are due" in new SandboxSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(ApplicationId.random, environment)

      when(mockUnusedApplicationsService.updateUnusedApplications()).thenReturn(Future.successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(eqTo(environment), eqTo(instant)))
        .thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(eqTo(unusedApplication), eqTo(environmentName)))
        .thenReturn(successful(true))
      when(mockUnusedApplicationsRepository.updateNotificationsSent(eqTo(environment), eqTo(unusedApplication.applicationId), eqTo(instant)))
        .thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsService).updateUnusedApplications()
      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)
      verify(mockUnusedApplicationsRepository, times(1)).updateNotificationsSent(environment, unusedApplication.applicationId, instant)
    }

    "not remove scheduled notification date if notifications could not be sent" in new SandboxSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(ApplicationId.random, environment)

      when(mockUnusedApplicationsService.updateUnusedApplications()).thenReturn(Future.successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, instant)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)).thenReturn(successful(false))

      await(underTest.runJob)

      verify(mockUnusedApplicationsService).updateUnusedApplications()
      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)
      verify(mockUnusedApplicationsRepository, times(0)).updateNotificationsSent(*, *[ApplicationId], *)
    }
  }

  "PRODUCTION job" should {
    "send notifications for applications that are due" in new ProductionSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(ApplicationId.random, environment)

      when(mockUnusedApplicationsService.updateUnusedApplications()).thenReturn(Future.successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, instant)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)).thenReturn(successful(true))
      when(mockUnusedApplicationsRepository.updateNotificationsSent(environment, unusedApplication.applicationId, instant)).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsService).updateUnusedApplications()
      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)
      verify(mockUnusedApplicationsRepository, times(1)).updateNotificationsSent(environment, unusedApplication.applicationId, instant)
    }

    "not remove scheduled notification date if notifications could not be sent" in new ProductionSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(ApplicationId.random, environment)

      when(mockUnusedApplicationsService.updateUnusedApplications()).thenReturn(Future.successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, instant)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)).thenReturn(successful(false))

      await(underTest.runJob)

      verify(mockUnusedApplicationsService).updateUnusedApplications()
      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication, environmentName)
      verify(mockUnusedApplicationsRepository, times(0)).updateNotificationsSent(*, *[ApplicationId], *)
    }
  }
}
