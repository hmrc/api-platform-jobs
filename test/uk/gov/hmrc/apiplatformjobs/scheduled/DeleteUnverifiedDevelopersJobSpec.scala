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

import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit.{HOURS, SECONDS}
import scala.concurrent.Future
import scala.concurrent.Future._
import scala.concurrent.duration.FiniteDuration

import org.scalatest.BeforeAndAfterAll

import play.api.http.Status.OK
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.UserId
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class DeleteUnverifiedDevelopersJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll {

  val FixedTimeNow: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  trait Setup extends BaseSetup {

    implicit val hc = HeaderCarrier()

    val mockLockKeeper: DeleteUnverifiedDevelopersJobLockService = new DeleteUnverifiedDevelopersJobLockService(mockLockRepository)

    when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(true))
    when(mockLockRepository.releaseLock(*, *)).thenReturn(Future.successful(()))

    val deleteUnverifiedDevelopersJobConfig: DeleteUnverifiedDevelopersJobConfig               =
      DeleteUnverifiedDevelopersJobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true, 5)
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector                         = mock[ThirdPartyDeveloperConnector]
    val mockSandboxThirdPartyApplicationConnector: SandboxThirdPartyApplicationConnector       = mock[SandboxThirdPartyApplicationConnector]
    val mockProductionThirdPartyApplicationConnector: ProductionThirdPartyApplicationConnector = mock[ProductionThirdPartyApplicationConnector]
    val underTest                                                                              = new DeleteUnverifiedDevelopersJob(
      mockLockKeeper,
      deleteUnverifiedDevelopersJobConfig,
      mockThirdPartyDeveloperConnector,
      mockSandboxThirdPartyApplicationConnector,
      mockProductionThirdPartyApplicationConnector
    )
  }

  trait SuccessfulSetup extends Setup {
    val developers = Seq(CoreUserDetails("joe.bloggs@example.com", UserId.random), CoreUserDetails("john.doe@example.com", UserId.random))
    when(mockThirdPartyDeveloperConnector.fetchUnverifiedDevelopers(*, *)(*)).thenReturn(successful(developers))
    when(mockSandboxThirdPartyApplicationConnector.fetchApplicationsByUserId(*[UserId])(*)).thenReturn(successful(Seq("sandbox 1")))
    when(mockSandboxThirdPartyApplicationConnector.removeCollaborator(*, *)(*)).thenReturn(successful(OK))
    when(mockProductionThirdPartyApplicationConnector.fetchApplicationsByUserId(*[UserId])(*)).thenReturn(successful(Seq("prod 1")))
    when(mockProductionThirdPartyApplicationConnector.removeCollaborator(*, *)(*)).thenReturn(successful(OK))
    when(mockThirdPartyDeveloperConnector.deleteDeveloper(*)(*)).thenReturn(successful(OK))
  }

  "DeleteUnverifiedDevelopersJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "delete unverified developers" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, times(1)).deleteDeveloper(eqTo("joe.bloggs@example.com"))(*)
      verify(mockThirdPartyDeveloperConnector, times(1)).deleteDeveloper(eqTo("john.doe@example.com"))(*)
      result.message shouldBe "DeleteUnverifiedDevelopersJob Job ran successfully."
    }

    "remove unverified developers as collaborators" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockSandboxThirdPartyApplicationConnector, times(1)).removeCollaborator(eqTo("sandbox 1"), eqTo("joe.bloggs@example.com"))(*)
      verify(mockSandboxThirdPartyApplicationConnector, times(1)).removeCollaborator(eqTo("sandbox 1"), eqTo("john.doe@example.com"))(*)
      verify(mockProductionThirdPartyApplicationConnector, times(1)).removeCollaborator(eqTo("prod 1"), eqTo("joe.bloggs@example.com"))(*)
      verify(mockProductionThirdPartyApplicationConnector, times(1)).removeCollaborator(eqTo("prod 1"), eqTo("john.doe@example.com"))(*)
      result.message shouldBe "DeleteUnverifiedDevelopersJob Job ran successfully."
    }

    "not execute if the job is already running" in new Setup {

      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(false))
      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).fetchUnverifiedDevelopers(*, *)(*)
      result.message shouldBe "DeleteUnverifiedDevelopersJob did not run because repository was locked by another instance of the scheduler."
    }

    "handle error when something fails" in new Setup {
      when(mockThirdPartyDeveloperConnector.fetchUnverifiedDevelopers(*, *)(*)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).deleteDeveloper(*)(*)
      result.message shouldBe "The execution of scheduled job DeleteUnverifiedDevelopersJob failed with error 'Failed'. " +
        "The next execution of the job will do retry."
    }
  }
}
