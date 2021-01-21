/*
 * Copyright 2021 HM Revenue & Customs
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

import java.util.UUID

import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class DeleteUnusedApplicationsJobSpec extends AsyncHmrcSpec with UnusedApplicationTestConfiguration with MongoSpecSupport {
  trait Setup {
    val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    val mockThirdPartyApplicationConnector: ThirdPartyApplicationConnector = mock[ThirdPartyApplicationConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]
  }

  trait SandboxSetup extends Setup {
    val environment: Environment = Environment.SANDBOX

    val underTest =
      new DeleteUnusedSandboxApplicationsJob(
        mockThirdPartyApplicationConnector,
        mockUnusedApplicationsRepository,
        defaultConfiguration,
        reactiveMongoComponent)
  }

  trait ProductionSetup extends Setup {
    val environment: Environment = Environment.PRODUCTION

    val underTest =
      new DeleteUnusedProductionApplicationsJob(
        mockThirdPartyApplicationConnector,
        mockUnusedApplicationsRepository,
        defaultConfiguration,
        reactiveMongoComponent)
  }

  "SANDBOX job" should {
    "should delete application from TPA and database" in new SandboxSetup {
      private val applicationId = UUID.randomUUID

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApplicationRecord(applicationId, environment))))
      when(mockThirdPartyApplicationConnector.deleteApplication(applicationId)).thenReturn(Future.successful(true))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(environment, applicationId)).thenReturn(Future.successful(true))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(applicationId)
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(environment, applicationId)
    }

    "should not delete from database if TPA delete failed" in new SandboxSetup {
      private val applicationId = UUID.randomUUID

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApplicationRecord(applicationId, environment))))
      when(mockThirdPartyApplicationConnector.deleteApplication(applicationId)).thenReturn(Future.successful(false))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(applicationId)
      verify(mockUnusedApplicationsRepository, times(0)).deleteUnusedApplicationRecord(environment, applicationId)
    }
  }

  "PRODUCTION job" should {
    "should delete application from TPA and database" in new ProductionSetup {
      private val applicationId = UUID.randomUUID

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApplicationRecord(applicationId, environment))))
      when(mockThirdPartyApplicationConnector.deleteApplication(applicationId)).thenReturn(Future.successful(true))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(environment, applicationId)).thenReturn(Future.successful(true))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(applicationId)
      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(environment, applicationId)
    }

    "should not delete from database if TPA delete failed" in new ProductionSetup {
      private val applicationId = UUID.randomUUID

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeDeleted(environment))
        .thenReturn(Future.successful(List(unusedApplicationRecord(applicationId, environment))))
      when(mockThirdPartyApplicationConnector.deleteApplication(applicationId)).thenReturn(Future.successful(false))

      await(underTest.runJob)

      verify(mockThirdPartyApplicationConnector).deleteApplication(applicationId)
      verify(mockUnusedApplicationsRepository, times(0)).deleteUnusedApplicationRecord(environment, applicationId)
    }
  }
}
