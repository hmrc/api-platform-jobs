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

package uk.gov.hmrc.apiplatformjobs.services

import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationNameData
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector}
import uk.gov.hmrc.apiplatformjobs.models.{ApplicationUsageDetails, Environments}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class UnusedApplicationsServiceSpec extends AsyncHmrcSpec with FixedClock {

  trait Setup extends BaseSetup {
    val mockProductionThirdPartyApplicationConnector: ProductionThirdPartyApplicationConnector = mock[ProductionThirdPartyApplicationConnector]
    val mockSandboxThirdPartyApplicationConnector: SandboxThirdPartyApplicationConnector       = mock[SandboxThirdPartyApplicationConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository                         = mock[UnusedApplicationsRepository]

    val underTest    =
      new UnusedApplicationsService(mockProductionThirdPartyApplicationConnector, mockSandboxThirdPartyApplicationConnector, mockUnusedApplicationsRepository)
    val sandboxAppId = ApplicationId.random
    val prodAppId    = ApplicationId.random
    val createdOn    = now.minusMonths(18).toInstant(ZoneOffset.UTC)
    val lastUseDate  = now.minusMonths(12).toInstant(ZoneOffset.UTC)
    val sandboxApp   = ApplicationUsageDetails(sandboxAppId, ApplicationNameData.one, Set("foo@bar.com".toLaxEmail), createdOn, Some(lastUseDate))
    val prodApp      = ApplicationUsageDetails(prodAppId, ApplicationNameData.one, Set("foo@bar.com".toLaxEmail), createdOn, Some(lastUseDate))
  }

  "updateUnusedApplications" should {
    "should remove SANDBOX app from unusedApplication collection" in new Setup {
      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List(sandboxApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environments.SANDBOX, sandboxAppId)).thenReturn(Future.successful(true))
      when(mockProductionThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List.empty))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 1
      result shouldBe List(true)

      verify(mockSandboxThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environments.SANDBOX), eqTo(sandboxAppId))
      verify(mockProductionThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
    }

    "should remove PRODUCTION app from unusedApplication collection" in new Setup {
      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List.empty))
      when(mockProductionThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List(prodApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environments.PRODUCTION, prodAppId)).thenReturn(Future.successful(true))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 1
      result shouldBe List(true)

      verify(mockSandboxThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verify(mockProductionThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environments.PRODUCTION), eqTo(prodAppId))
    }

    "should remove no apps from unusedApplication collection" in new Setup {
      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List.empty))
      when(mockProductionThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List.empty))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 0
      result shouldBe List.empty

      verify(mockSandboxThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verify(mockProductionThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verifyZeroInteractions(mockUnusedApplicationsRepository)
    }

    "should remove SANDBOX AND PRODUCTION apps not to be deleted from unusedApplication collection" in new Setup {
      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List(sandboxApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environments.SANDBOX, sandboxAppId)).thenReturn(Future.successful(true))
      when(mockProductionThirdPartyApplicationConnector.applicationSearch(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(List(prodApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environments.PRODUCTION, prodAppId)).thenReturn(Future.successful(true))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 2
      result shouldBe List(true, true)

      verify(mockSandboxThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environments.SANDBOX), eqTo(sandboxAppId))
      verify(mockProductionThirdPartyApplicationConnector).applicationSearch(eqTo(None), eqTo(false))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environments.PRODUCTION), eqTo(prodAppId))
    }
  }
}
