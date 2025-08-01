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

import org.mockito.captor.ArgCaptor
import org.scalatest.BeforeAndAfterAll

import play.api.http.Status.OK
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.Lock

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborators._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, Collaborator}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommand
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.RemoveCollaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors.ScheduledJob
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.connectors._
import uk.gov.hmrc.apiplatformjobs.models.HasSucceeded
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class DeleteUnregisteredDevelopersJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationWithCollaboratorsFixtures {

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
    val registeredUser = CoreUserDetails("bob@example.com".toLaxEmail, UserId.random)
    val johnDoe        = CoreUserDetails("john.doe@example.com".toLaxEmail, UserId.random)
    val joeBloggs      = CoreUserDetails("joe.bloggs@example.com".toLaxEmail, UserId.random)

    val excludedUsers   = deleteUnregisteredDevelopersJobConfig.excludedEmails.map(email => CoreUserDetails(email, UserId.random)).toList
    val excludedCollabs = excludedUsers.map(toAdministrator)

    val deletableProductionCollabs = Set[Collaborator](toAdministrator(johnDoe), toDeveloper(joeBloggs))
    val deletableSandboxCollabs    = Set[Collaborator](toAdministrator(joeBloggs), toDeveloper(johnDoe))

    val productionCollabs = deletableProductionCollabs + toAdministrator(registeredUser) ++ excludedCollabs
    val sandboxCollabs    = deletableSandboxCollabs ++ excludedCollabs

    val productionApp = standardApp.withId(ApplicationIdData.one).withCollaborators(productionCollabs).withEnvironment(Environment.PRODUCTION)
    val sandboxApp    = standardApp.withId(ApplicationIdData.two).withCollaborators(sandboxCollabs).withEnvironment(Environment.SANDBOX)

    val unregisteredUsers = List(joeBloggs, johnDoe) ++ excludedUsers
    when(mockThirdPartyDeveloperConnector.fetchExpiredUnregisteredDevelopers(*)(*)).thenReturn(successful(unregisteredUsers))
    when(mockTpoConnector.fetchApplicationsByUserId(*[UserId])(*)).thenReturn(successful(Seq(sandboxApp, productionApp)))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.SANDBOX), *[ApplicationId], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.PRODUCTION), *[ApplicationId], *, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockThirdPartyDeveloperConnector.deleteUnregisteredDeveloper(*[LaxEmailAddress])(*)).thenReturn(successful(OK))

    def verifyRemovalOfCollaborators(environment: Environment, appId: ApplicationId, expectedCollaborators: Set[Collaborator]) = {
      val commandCaptor = ArgCaptor[ApplicationCommand]
      verify(mockTpoCmdConnector, times(expectedCollaborators.size)).dispatchToEnvironment(eqTo(environment), eqTo(appId), commandCaptor, *)(*)

      commandCaptor.values.foreach {
        case RemoveCollaborator(ScheduledJob("Delete-UnregisteredUser"), _, _) => succeed
        case c @ _                                                             => fail(s"Not the expected command Delete-UnregisteredUser but got $c")
      }

      val collaborators: Set[Collaborator] = commandCaptor.values.collect {
        case RemoveCollaborator(_, collaborator, _) => collaborator
      }.toSet

      collaborators shouldBe expectedCollaborators
    }

    def toAdministrator(user: CoreUserDetails) = Administrator(user.id, user.email)

    def toDeveloper(user: CoreUserDetails) = Developer(user.id, user.email)
  }

  "DeleteUnregisteredDevelopersJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "delete unregistered developers" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, times(1)).deleteUnregisteredDeveloper(eqTo(joeBloggs.email))(*)
      verify(mockThirdPartyDeveloperConnector, times(1)).deleteUnregisteredDeveloper(eqTo(johnDoe.email))(*)
      result.message shouldBe "DeleteUnregisteredDevelopersJob Job ran successfully."
    }

    "remove unregistered developers as collaborators" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verifyRemovalOfCollaborators(Environment.SANDBOX, sandboxApp.id, deletableSandboxCollabs)
      verifyRemovalOfCollaborators(Environment.PRODUCTION, productionApp.id, deletableProductionCollabs)
      // called twice as fetchExpiredUnregisteredDevelopers returns two records
      verify(mockThirdPartyDeveloperConnector, times(2)).deleteUnregisteredDeveloper(*[LaxEmailAddress])(*)

      result.message shouldBe "DeleteUnregisteredDevelopersJob Job ran successfully."
    }

    "not remove developer from TPD if one of the calls to TPA remove collaborator fails " in new SuccessfulSetup {
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.SANDBOX), *[ApplicationId], *, *)(*)).thenReturn(failed(new RuntimeException("Failed")))
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.PRODUCTION), *[ApplicationId], *, *)(*)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verifyRemovalOfCollaborators(Environment.SANDBOX, sandboxApp.id, deletableSandboxCollabs)
      verifyRemovalOfCollaborators(Environment.PRODUCTION, productionApp.id, deletableProductionCollabs)
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
