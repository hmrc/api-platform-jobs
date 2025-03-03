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

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationNameData, DeleteRestrictionType}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyOrchestratorConnector
import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class UnusedApplicationsServiceSpec extends AsyncHmrcSpec with FixedClock {

  trait Setup extends BaseSetup {
    val mockTpoConnector: ThirdPartyOrchestratorConnector              = mock[ThirdPartyOrchestratorConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]

    val underTest         =
      new UnusedApplicationsService(mockTpoConnector, mockUnusedApplicationsRepository)
    val sandboxAppId      = ApplicationId.random
    val prodAppId         = ApplicationId.random
    val createdOn         = now.minusMonths(18).toInstant(ZoneOffset.UTC)
    val lastUseDate       = now.minusMonths(12).toInstant(ZoneOffset.UTC)
    val deleteRestriction = DeleteRestrictionType.DO_NOT_DELETE
    val sandboxApp        = ApplicationUsageDetails(sandboxAppId, ApplicationNameData.one, Set("foo@bar.com".toLaxEmail), createdOn, Some(lastUseDate))
    val prodApp           = ApplicationUsageDetails(prodAppId, ApplicationNameData.one, Set("foo@bar.com".toLaxEmail), createdOn, Some(lastUseDate))
  }

  "updateUnusedApplications" should {
    "should remove SANDBOX app from unusedApplication collection" in new Setup {
      when(mockTpoConnector.applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List(sandboxApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environment.SANDBOX, sandboxAppId)).thenReturn(Future.successful(true))
      when(mockTpoConnector.applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List.empty))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 1
      result shouldBe List(true)

      verify(mockTpoConnector).applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environment.SANDBOX), eqTo(sandboxAppId))
      verify(mockTpoConnector).applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction))
    }

    "should remove PRODUCTION app from unusedApplication collection" in new Setup {
      when(mockTpoConnector.applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List.empty))
      when(mockTpoConnector.applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List(prodApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environment.PRODUCTION, prodAppId)).thenReturn(Future.successful(true))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 1
      result shouldBe List(true)

      verify(mockTpoConnector).applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction))
      verify(mockTpoConnector).applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environment.PRODUCTION), eqTo(prodAppId))
    }

    "should remove no apps from unusedApplication collection" in new Setup {
      when(mockTpoConnector.applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List.empty))
      when(mockTpoConnector.applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List.empty))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 0
      result shouldBe List.empty

      verify(mockTpoConnector).applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction))
      verify(mockTpoConnector).applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction))
      verifyZeroInteractions(mockUnusedApplicationsRepository)
    }

    "should remove SANDBOX AND PRODUCTION apps not to be deleted from unusedApplication collection" in new Setup {
      when(mockTpoConnector.applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List(sandboxApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environment.SANDBOX, sandboxAppId)).thenReturn(Future.successful(true))
      when(mockTpoConnector.applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction)))
        .thenReturn(Future.successful(List(prodApp)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(Environment.PRODUCTION, prodAppId)).thenReturn(Future.successful(true))

      val result = await(underTest.updateUnusedApplications())
      result.size shouldBe 2
      result shouldBe List(true, true)

      verify(mockTpoConnector).applicationSearch(eqTo(Environment.SANDBOX), eqTo(None), eqTo(deleteRestriction))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environment.SANDBOX), eqTo(sandboxAppId))
      verify(mockTpoConnector).applicationSearch(eqTo(Environment.PRODUCTION), eqTo(None), eqTo(deleteRestriction))
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environment.PRODUCTION), eqTo(prodAppId))
    }
  }
}
