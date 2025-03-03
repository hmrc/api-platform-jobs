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

import java.time.Instant
import java.util.concurrent.TimeUnit.{HOURS, SECONDS}
import scala.concurrent.Future
import scala.concurrent.Future._
import scala.concurrent.duration.FiniteDuration

import org.scalatest.BeforeAndAfterAll

import play.api.http.Status.OK
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.Lock

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, Collaborator, Collaborators}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.connectors._
import uk.gov.hmrc.apiplatformjobs.models.HasSucceeded
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class DeleteUnregisteredDevelopersJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationWithCollaboratorsFixtures {
  val joeBloggs = "joe.bloggs@example.com".toLaxEmail
  val johnDoe   = "john.doe@example.com".toLaxEmail

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  trait Setup extends BaseSetup {
    implicit val hc: HeaderCarrier                                 = HeaderCarrier()
    val lockKeeperSuccess: () => Boolean                           = () => true
    val mockLockKeeper: DeleteUnregisteredDevelopersJobLockService = new DeleteUnregisteredDevelopersJobLockService(mockLockRepository)

    when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(Some(Lock("", "", Instant.now, Instant.now))))
    when(mockLockRepository.releaseLock(*, *)).thenReturn(Future.successful(()))

    val deleteUnregisteredDevelopersJobConfig: DeleteUnregisteredDevelopersJobConfig =
      DeleteUnregisteredDevelopersJobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true, 5)
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector               = mock[ThirdPartyDeveloperConnector]
    val mockTpoConnector: ThirdPartyOrchestratorConnector                            = mock[ThirdPartyOrchestratorConnector]
    val mockTpoCmdConnector: TpoApplicationCommandConnector                          = mock[TpoApplicationCommandConnector]

    val underTest = new DeleteUnregisteredDevelopersJob(
      mockLockKeeper,
      deleteUnregisteredDevelopersJobConfig,
      mockThirdPartyDeveloperConnector,
      mockTpoConnector,
      mockTpoCmdConnector
    )
  }

  trait SuccessfulSetup extends Setup {
    val productionAppId = ApplicationIdData.one
    val sandboxAppId    = ApplicationIdData.two

    val userId      = UserId.random
    val user1Id     = UserId.random
    val user1Email  = "bob@example.com".toLaxEmail
    val johnDoeId   = UserId.random
    val joeBloggsId = UserId.random

    val appAADUsers =
      Set[Collaborator](Collaborators.Administrator(user1Id, user1Email), Collaborators.Administrator(johnDoeId, johnDoe), Collaborators.Developer(joeBloggsId, joeBloggs))
    val appADUsers  = Set[Collaborator](Collaborators.Administrator(joeBloggsId, joeBloggs), Collaborators.Developer(johnDoeId, johnDoe))

    val prodApp    = standardApp.withId(productionAppId).withCollaborators(appAADUsers).withEnvironment(Environment.PRODUCTION)
    val sandBoxApp = standardApp.withId(sandboxAppId).withCollaborators(appADUsers).withEnvironment(Environment.SANDBOX)

    val developers = Seq(CoreUserDetails(joeBloggs, joeBloggsId), CoreUserDetails(johnDoe, johnDoeId))
    when(mockThirdPartyDeveloperConnector.fetchExpiredUnregisteredDevelopers(*)(*)).thenReturn(successful(developers))
    when(mockTpoConnector.fetchApplicationsByUserId(*[UserId])(*)).thenReturn(successful(Seq(sandBoxApp, prodApp)))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.SANDBOX), *[ApplicationId], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.PRODUCTION), *[ApplicationId], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockThirdPartyDeveloperConnector.deleteUnregisteredDeveloper(*[LaxEmailAddress])(*)).thenReturn(successful(OK))
  }

  "DeleteUnregisteredDevelopersJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "delete unregistered developers" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, times(1)).deleteUnregisteredDeveloper(eqTo(joeBloggs))(*)
      verify(mockThirdPartyDeveloperConnector, times(1)).deleteUnregisteredDeveloper(eqTo(johnDoe))(*)
      result.message shouldBe "DeleteUnregisteredDevelopersJob Job ran successfully."
    }

    "remove unregistered developers as collaborators" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.SANDBOX),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Administrator(_, joeBloggs), _) => }),
        *
      )(*)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.SANDBOX),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Developer(_, johnDoe), _) => }),
        *
      )(*)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.PRODUCTION),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Administrator(_, joeBloggs), _) => }),
        *
      )(*)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.PRODUCTION),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Developer(_, johnDoe), _) => }),
        *
      )(*)
      // called twice as fetchExpiredUnregisteredDevelopers returns two records
      verify(mockThirdPartyDeveloperConnector, times(2)).deleteUnregisteredDeveloper(*[LaxEmailAddress])(*)

      result.message shouldBe "DeleteUnregisteredDevelopersJob Job ran successfully."
    }

    "not remove developer from TPD if one of the calls to TPA remove collaborator fails " in new SuccessfulSetup {
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.SANDBOX), *[ApplicationId], *, *)(*)).thenReturn(failed(new RuntimeException("Failed")))
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.PRODUCTION), *[ApplicationId], *, *)(*)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.SANDBOX),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Developer(_, joeBloggs), _) => }),
        *
      )(*)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.SANDBOX),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Developer(_, johnDoe), _) => }),
        *
      )(*)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.PRODUCTION),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Developer(_, joeBloggs), _) => }),
        *
      )(*)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.PRODUCTION),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnregisteredUser"), Collaborators.Developer(_, johnDoe), _) => }),
        *
      )(*)
      verify(mockThirdPartyDeveloperConnector, never).deleteUnregisteredDeveloper(*[LaxEmailAddress])(*)
      result.message shouldBe "The execution of scheduled job DeleteUnregisteredDevelopersJob failed with error 'Failed'. The next execution of the job will do retry."
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false
      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(None))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).fetchExpiredUnregisteredDevelopers(*)(*)
      result.message shouldBe "DeleteUnregisteredDevelopersJob did not run because repository was locked by another instance of the scheduler."
    }

    "handle error when something fails" in new Setup {
      when(mockThirdPartyDeveloperConnector.fetchExpiredUnregisteredDevelopers(*)(*)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).deleteUnregisteredDeveloper(*[LaxEmailAddress])(*)
      result.message shouldBe "The execution of scheduled job DeleteUnregisteredDevelopersJob failed with error 'Failed'. " +
        "The next execution of the job will do retry."
    }
  }
}
