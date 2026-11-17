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
import scala.concurrent.Future.*
import scala.concurrent.duration.FiniteDuration

import org.scalatest.BeforeAndAfterAll

import play.api.http.Status.OK
import uk.gov.hmrc.http.{BadRequestException, HeaderCarrier}
import uk.gov.hmrc.mongo.lock.Lock

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, Collaborator, Collaborators}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.common.domain.models.*
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax.toLaxEmail
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.organisations.domain
import uk.gov.hmrc.apiplatform.modules.organisations.domain.models.{Organisation, OrganisationName}

import uk.gov.hmrc.apiplatformjobs.connectors.*
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.models.HasSucceeded
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class DeleteUnverifiedDevelopersJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with FixedClock with ApplicationWithCollaboratorsFixtures {

  val joeBloggs = "joe.bloggs@example.com".toLaxEmail
  val johnDoe   = "john.doe@example.com".toLaxEmail

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  trait Setup extends BaseSetup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(Some(Lock("", "", Instant.now, Instant.now))))
    when(mockLockRepository.releaseLock(*, *)).thenReturn(Future.successful(()))

    val deleteUnverifiedDevelopersJobConfig: DeleteUnverifiedDevelopersJobConfig =
      DeleteUnverifiedDevelopersJobConfig(FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true, 5)
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector           = mock[ThirdPartyDeveloperConnector]
    val mockTpoConnector: ThirdPartyOrchestratorConnector                        = mock[ThirdPartyOrchestratorConnector]
    val mockTpoCmdConnector: TpoApplicationCommandConnector                      = mock[TpoApplicationCommandConnector]
    val mockOrganisationConnector: OrganisationConnector                         = mock[OrganisationConnector]

    val underTest = new DeleteUnverifiedDevelopersJob(
      mockLockRepository,
      deleteUnverifiedDevelopersJobConfig,
      mockThirdPartyDeveloperConnector,
      mockTpoConnector,
      mockTpoCmdConnector,
      mockOrganisationConnector,
      clock
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

    val prodApp    = standardApp.withId(productionAppId).withCollaborators(appAADUsers).withEnvironment(Environment.Production)
    val SandboxApp = standardApp.withId(sandboxAppId).withCollaborators(appADUsers).withEnvironment(Environment.Sandbox)

    val organisationId = OrganisationId.random
    val collaborator1  = domain.models.Collaborator(domain.models.Collaborator.Roles.Administrator, joeBloggsId)
    val collaborator2  = domain.models.Collaborator(domain.models.Collaborator.Roles.Member, johnDoeId)
    val organisation   = Organisation(organisationId, OrganisationName("Org name"), Organisation.OrganisationType.UkLimitedCompany, instant, Set(collaborator1, collaborator2))

    val developers = Seq(CoreUserDetails(joeBloggs, joeBloggsId), CoreUserDetails(johnDoe, johnDoeId))

    when(mockThirdPartyDeveloperConnector.fetchUnverifiedDevelopers(*, *)(using *)).thenReturn(successful(developers))
    when(mockTpoConnector.fetchApplicationsByUserId(*[UserId])).thenReturn(successful(Seq(SandboxApp, prodApp)))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Sandbox), *[ApplicationId], *, *)(using *)).thenReturn(successful(HasSucceeded))
    when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Production), *[ApplicationId], *, *)(using *)).thenReturn(successful(HasSucceeded))
    when(mockThirdPartyDeveloperConnector.deleteDeveloper(*[LaxEmailAddress])(using *)).thenReturn(successful(OK))
    when(mockOrganisationConnector.fetchOrganisationsByUserId(*[UserId])(using *)).thenReturn(successful(List(organisation)))
    when(mockOrganisationConnector.removeCollaboratorFromOrganisation(*[OrganisationId], *[UserId], *[LaxEmailAddress])(using *)).thenReturn(successful(organisation))
  }

  "DeleteUnverifiedDevelopersJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "delete unverified developers" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, times(1)).deleteDeveloper(eqTo(joeBloggs))(using *)
      verify(mockThirdPartyDeveloperConnector, times(1)).deleteDeveloper(eqTo(johnDoe))(using *)
      result.message shouldBe "Job named DeleteUnverifiedDevelopersJob ran, and completed with result succeeded"
    }

    "remove unverified developers as collaborators" in new SuccessfulSetup {
      val result: underTest.Result = await(underTest.execute)

      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Sandbox),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Administrator(_, `joeBloggs`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Sandbox),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Developer(_, `johnDoe`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Production),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Developer(_, `joeBloggs`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Production),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Administrator(_, `johnDoe`), _) => }),
        *
      )(using *)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(joeBloggsId), eqTo(joeBloggs))(using *)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(johnDoeId), eqTo(johnDoe))(using *)

      // called twice as tpd fetchExpiredUnregisteredDevelopers returns 2 records
      verify(mockThirdPartyDeveloperConnector, times(2)).deleteDeveloper(*[LaxEmailAddress])(using *)

      result.message shouldBe "Job named DeleteUnverifiedDevelopersJob ran, and completed with result succeeded"
    }

    "not remove developer from TPD if one of the calls to TPA remove collaborator fails" in new SuccessfulSetup {
      when(mockTpoCmdConnector.dispatchToEnvironment(eqTo(Environment.Production), *[ApplicationId], *, *)(using *)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Sandbox),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Administrator(_, `joeBloggs`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Sandbox),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Developer(_, `johnDoe`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Production),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Developer(_, `joeBloggs`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Production),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Administrator(_, `johnDoe`), _) => }),
        *
      )(using *)
      verify(mockThirdPartyDeveloperConnector, never).deleteDeveloper(*[LaxEmailAddress])(using *)

      result.message shouldBe "Job named DeleteUnverifiedDevelopersJob ran, and completed with result failed due to Failed"
    }

    "not remove developer from TPD if one of the calls to api-platform-organisation remove collaborator fails" in new SuccessfulSetup {
      when(mockOrganisationConnector.removeCollaboratorFromOrganisation(*[OrganisationId], *[UserId], *[LaxEmailAddress])(using *))
        .thenReturn(failed(new BadRequestException("Failed because of x")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Sandbox),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Administrator(_, `joeBloggs`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Sandbox),
        eqTo(sandboxAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Developer(_, `johnDoe`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Production),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Developer(_, `joeBloggs`), _) => }),
        *
      )(using *)
      verify(mockTpoCmdConnector, atLeastOnce).dispatchToEnvironment(
        eqTo(Environment.Production),
        eqTo(productionAppId),
        argMatching({ case ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("Delete-UnverifiedUser"), Collaborators.Administrator(_, `johnDoe`), _) => }),
        *
      )(using *)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(joeBloggsId), eqTo(joeBloggs))(using *)
      verify(mockOrganisationConnector, times(1)).removeCollaboratorFromOrganisation(eqTo(organisationId), eqTo(johnDoeId), eqTo(johnDoe))(using *)
      verify(mockThirdPartyDeveloperConnector, never).deleteDeveloper(*[LaxEmailAddress])(using *)

      result.message shouldBe "Job named DeleteUnverifiedDevelopersJob ran, and completed with result failed due to Failed because of x"
    }

    "not execute if the job is already running" in new Setup {

      when(mockLockRepository.takeLock(*, *, *)).thenReturn(Future.successful(None))
      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).fetchUnverifiedDevelopers(*, *)(using *)
      result.message shouldBe "Job named DeleteUnverifiedDevelopersJob cannot acquire Mongo lock so not running"
    }

    "handle error when something fails" in new Setup {
      when(mockThirdPartyDeveloperConnector.fetchUnverifiedDevelopers(*, *)(using *)).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).deleteDeveloper(*[LaxEmailAddress])(using *)
      result.message shouldBe "Job named DeleteUnverifiedDevelopersJob ran, and completed with result failed due to Failed"
    }
  }
}
