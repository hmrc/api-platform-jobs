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
import uk.gov.hmrc.http.{BadRequestException, HeaderCarrier}
import uk.gov.hmrc.mongo.lock.Lock

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborators._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, Collaborator}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommand
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.RemoveCollaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors.ScheduledJob
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax.toLaxEmail
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.organisations.domain
import uk.gov.hmrc.apiplatform.modules.organisations.domain.models.{Organisation, OrganisationName}

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
      DeleteUnregisteredDevelopersJobConfig(
        FiniteDuration(60, SECONDS),
        FiniteDuration(24, HOURS),
        enabled = true,
        5,
        List(
          LaxEmailAddress("isregistered@example.com"),
          LaxEmailAddress("notregistered@example.com")
        )
      )
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector               = mock[ThirdPartyDeveloperConnector]
    val mockTpoConnector: ThirdPartyOrchestratorConnector                            = mock[ThirdPartyOrchestratorConnector]
    val mockTpoCmdConnector: TpoApplicationCommandConnector                          = mock[TpoApplicationCommandConnector]
    val mockOrganisationConnector: OrganisationConnector                             = mock[OrganisationConnector]

    val underTest = new DeleteUnregisteredDevelopersJob(
      mockLockRepository,
      deleteUnregisteredDevelopersJobConfig,
      mockThirdPartyDeveloperConnector,
      mockTpoConnector,
      mockTpoCmdConnector,
      mockOrganisationConnector
    )
  }

  trait SuccessfulSetup extends Setup {
    val registeredUser = CoreUserDetails("bob@example.com".toLaxEmail, UserId.random)
    val johnDoeId      = UserId.random
    val joeBloggsId    = UserId.random
    val johnDoe        = CoreUserDetails("john.doe@example.com".toLaxEmail, johnDoeId)
    val joeBloggs      = CoreUserDetails("joe.bloggs@example.com".toLaxEmail, joeBloggsId)

    val excludedUsers   = deleteUnregisteredDevelopersJobConfig.excludedEmails.map(email => CoreUserDetails(email, UserId.random))
    val excludedCollabs = excludedUsers.map(toAdministrator)

    val deletableProductionCollabs = Set[Collaborator](toAdministrator(johnDoe), toDeveloper(joeBloggs))
    val deletableSandboxCollabs    = Set[Collaborator](toAdministrator(joeBloggs), toDeveloper(johnDoe))

    val ProductionCollabs = deletableProductionCollabs + toAdministrator(registeredUser) ++ excludedCollabs
    val SandboxCollabs    = deletableSandboxCollabs ++ excludedCollabs

    val ProductionApp = standardApp.withId(ApplicationIdData.one).withCollaborators(ProductionCollabs).withEnvironment(Environment.Production)
    val SandboxApp    = standardApp.withId(ApplicationIdData.two).withCollaborators(SandboxCollabs).withEnvironment(Environment.Sandbox)

    val organisationId = OrganisationId.random
    val collaborator1  = domain.models.Collaborator(domain.models.Collaborator.Roles.Administrator, joeBloggsId)
    val collaborator2  = domain.models.Collaborator(domain.models.Collaborator.Roles.Member, johnDoeId)
    val organisation   = Organisation(organisationId, OrganisationName("Org name"), Organisation.OrganisationType.UkLimitedCompany, instant, Set(collaborator1, collaborator2))

    val unregisteredUsers = List(joeBloggs, johnDoe) ++ excludedUsers

    when(mockThirdPartyDeveloperConnector.fetchExpiredUnregisteredDevelopers(*)(using *)).thenReturn(successful(unregisteredUsers))
    when(mockTpoConnector.fetchApplicationsByUserId(*[UserId])).thenReturn(successful(Seq(SandboxApp, ProductionApp)))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Sandbox), *[ApplicationId], *, *)(using *)).thenReturn(successful(HasSucceeded))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Production), *[ApplicationId], *, *)(using *)).thenReturn(successful(HasSucceeded))
    when(mockThirdPartyDeveloperConnector.deleteUnregisteredDeveloper(*[LaxEmailAddress])(using *)).thenReturn(successful(OK))
    when(mockOrganisationConnector.fetchOrganisationsByUserId(*[UserId])(using *)).thenReturn(successful(List(organisation)))
    when(mockOrganisationConnector.removeCollaboratorFromOrganisation(*[OrganisationId], *[UserId], *[LaxEmailAddress])(using *)).thenReturn(successful(organisation))

    def verifyRemovalOfCollaborators(environment: Environment, appId: ApplicationId, expectedCollaborators: Set[Collaborator]) = {
      val commandCaptor = ArgCaptor[ApplicationCommand]
      verify(mockTpoCmdConnector, times(expectedCollaborators.size)).dispatchToEnvironment(eqTo(environment), eqTo(appId), commandCaptor, *)(using *)

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

      verify(mockThirdPartyDeveloperConnector, times(1)).deleteUnregisteredDeveloper(eqTo(joeBloggs.email))(using *)
      verify(mockThirdPartyDeveloperConnector, times(1)).deleteUnregisteredDeveloper(eqTo(johnDoe.email))(using *)
      result.message shouldBe "Job named DeleteUnregisteredDevelopersJob ran, and completed with result succeeded"
    }

    "remove unregistered developers as collaborators" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verifyRemovalOfCollaborators(Environment.Sandbox, SandboxApp.id, deletableSandboxCollabs)
      verifyRemovalOfCollaborators(Environment.Production, ProductionApp.id, deletableProductionCollabs)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(joeBloggsId), eqTo(joeBloggs.email))(using *)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(johnDoeId), eqTo(johnDoe.email))(using *)
      // called twice as fetchExpiredUnregisteredDevelopers returns two records
      verify(mockThirdPartyDeveloperConnector, times(2)).deleteUnregisteredDeveloper(*[LaxEmailAddress])(using *)

      result.message shouldBe "Job named DeleteUnregisteredDevelopersJob ran, and completed with result succeeded"
    }

    "not remove developer from TPD if one of the calls to TPA remove collaborator fails" in new SuccessfulSetup {
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Sandbox), *[ApplicationId], *, *)(using *)).thenReturn(failed(new RuntimeException("Failed")))
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Production), *[ApplicationId], *, *)(using *)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verifyRemovalOfCollaborators(Environment.Sandbox, SandboxApp.id, deletableSandboxCollabs)
      verifyRemovalOfCollaborators(Environment.Production, ProductionApp.id, deletableProductionCollabs)
      verify(mockThirdPartyDeveloperConnector, never).deleteUnregisteredDeveloper(*[LaxEmailAddress])(using *)
      result.message shouldBe "Job named DeleteUnregisteredDevelopersJob ran, and completed with result failed due to Failed"
    }

    "not remove developer from TPD if one of the calls to api-platform-organisation remove collaborator fails" in new SuccessfulSetup {
      when(mockOrganisationConnector.removeCollaboratorFromOrganisation(*[OrganisationId], *[UserId], *[LaxEmailAddress])(using *))
        .thenReturn(failed(new BadRequestException("Failed because of x")))

      val result: underTest.Result = await(underTest.execute)

      verifyRemovalOfCollaborators(Environment.Sandbox, SandboxApp.id, deletableSandboxCollabs)
      verifyRemovalOfCollaborators(Environment.Production, ProductionApp.id, deletableProductionCollabs)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(joeBloggsId), eqTo(joeBloggs.email))(using *)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(johnDoeId), eqTo(johnDoe.email))(using *)
      verify(mockThirdPartyDeveloperConnector, never).deleteUnregisteredDeveloper(*[LaxEmailAddress])(using *)
      result.message shouldBe "Job named DeleteUnregisteredDevelopersJob ran, and completed with result failed due to Failed because of x"
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false
      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(None))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).fetchExpiredUnregisteredDevelopers(*)(using *)
      result.message shouldBe "Job named DeleteUnregisteredDevelopersJob cannot acquire Mongo lock so not running"
    }

    "handle error when something fails" in new Setup {
      when(mockThirdPartyDeveloperConnector.fetchExpiredUnregisteredDevelopers(*)(using *)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).deleteUnregisteredDeveloper(*[LaxEmailAddress])(using *)
      result.message shouldBe "Job named DeleteUnregisteredDevelopersJob ran, and completed with result failed due to Failed"
    }
  }
}
