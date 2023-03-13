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

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatformjobs.models.{ApplicationUpdateFailureResult, ApplicationUpdateSuccessResult, Environment}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

class DeleteUnusedApplicationsJobSpec extends AsyncHmrcSpec with UnusedApplicationTestConfiguration {

  trait Setup extends BaseSetup {
    val mockThirdPartyApplicationConnector: ThirdPartyApplicationConnector = mock[ThirdPartyApplicationConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository     = mock[UnusedApplicationsRepository]
    when(mockUnusedApplicationsRepository.clock).thenReturn(fixedClock)
  }

  trait SandboxSetup extends Setup {
    val environment: Environment = Environment.SANDBOX

    val underTest =
      new DeleteUnusedSandboxApplicationsJob(mockThirdPartyApplicationConnector, mockUnusedApplicationsRepository, defaultConfiguration, fixedClock, mockLockRepository)
  }

  trait ProductionSetup extends Setup {
    val environment: Environment = Environment.PRODUCTION

    val underTest =
      new DeleteUnusedProductionApplicationsJob(mockThirdPartyApplicationConnector, mockUnusedApplicationsRepository, defaultConfiguration, fixedClock, mockLockRepository)
  }

  "SANDBOX job" should {
    "should delete application from TPA and database" in new SandboxSetup {
      private val applicationId = ApplicationId.random
      private val unusedApp     = unusedApplicationRecord(applicationId, environment)
      private val reasons       = s"Application automatically deleted because it has not been used since ${unusedApp.lastInteractionDate}"

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApp)))
      when(mockThirdPartyApplicationConnector.deleteApplication(eqTo(applicationId), eqTo(underTest.name), eqTo(reasons), eqTo(LocalDateTime.now(fixedClock)))(*))
        .thenReturn(Future.successful(ApplicationUpdateSuccessResult))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(environment, applicationId)).thenReturn(Future.successful(true))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(
        eqTo(applicationId),
        eqTo("DeleteUnusedApplicationsJob.SANDBOX"),
        eqTo(reasons),
        eqTo(LocalDateTime.now(fixedClock))
      )(*)
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(environment, applicationId)
    }

    "should not delete from database if TPA delete failed" in new SandboxSetup {
      private val applicationId = ApplicationId.random
      private val unusedApp     = unusedApplicationRecord(applicationId, environment)
      private val reasons       = s"Application automatically deleted because it has not been used since ${unusedApp.lastInteractionDate}"

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApp)))
      when(mockThirdPartyApplicationConnector.deleteApplication(*[ApplicationId], *, *, *)(*)).thenReturn(Future.successful(ApplicationUpdateFailureResult))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(
        eqTo(applicationId),
        eqTo("DeleteUnusedApplicationsJob.SANDBOX"),
        eqTo(reasons),
        eqTo(LocalDateTime.now(fixedClock))
      )(*)
      verify(mockUnusedApplicationsRepository, times(0)).deleteUnusedApplicationRecord(environment, applicationId)
    }
  }

  "PRODUCTION job" should {
    "should delete application from TPA and database" in new ProductionSetup {
      private val applicationId = ApplicationId.random
      private val unusedApp     = unusedApplicationRecord(applicationId, environment)
      private val reasons       = s"Application automatically deleted because it has not been used since ${unusedApp.lastInteractionDate}"

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApp)))
      when(mockThirdPartyApplicationConnector.deleteApplication(*[ApplicationId], *, *, *)(*)).thenReturn(Future.successful(ApplicationUpdateSuccessResult))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(environment, applicationId)).thenReturn(Future.successful(true))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(
        eqTo(applicationId),
        eqTo("DeleteUnusedApplicationsJob.PRODUCTION"),
        eqTo(reasons),
        eqTo(LocalDateTime.now(fixedClock))
      )(*)
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(environment, applicationId)
    }

    "should not delete from database if TPA delete failed" in new ProductionSetup {
      private val applicationId = ApplicationId.random
      private val unusedApp     = unusedApplicationRecord(applicationId, environment)
      private val reasons       = s"Application automatically deleted because it has not been used since ${unusedApp.lastInteractionDate}"

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApp)))
      when(mockThirdPartyApplicationConnector.deleteApplication(*[ApplicationId], *, *, *)(*)).thenReturn(Future.successful(ApplicationUpdateFailureResult))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(
        eqTo(applicationId),
        eqTo("DeleteUnusedApplicationsJob.PRODUCTION"),
        eqTo(reasons),
        eqTo(LocalDateTime.now(fixedClock))
      )(*)
      verify(mockUnusedApplicationsRepository, times(0)).deleteUnusedApplicationRecord(environment, applicationId)
    }
  }
}
