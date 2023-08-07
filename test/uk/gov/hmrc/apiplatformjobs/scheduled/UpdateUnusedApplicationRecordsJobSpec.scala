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

import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.Random

import org.mockito.ArgumentCaptor

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.DeveloperResponse
import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.Environments._
import uk.gov.hmrc.apiplatformjobs.models.{Environment, _}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class UpdateUnusedApplicationRecordsJobSpec extends AsyncHmrcSpec with UnusedApplicationTestConfiguration {

  trait Setup extends BaseSetup {
    val environmentName                                                                        = "Test Environment"
    val mockSandboxThirdPartyApplicationConnector: SandboxThirdPartyApplicationConnector       = mock[SandboxThirdPartyApplicationConnector]
    val mockProductionThirdPartyApplicationConnector: ProductionThirdPartyApplicationConnector = mock[ProductionThirdPartyApplicationConnector]
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector                         = mock[ThirdPartyDeveloperConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository                         = mock[UnusedApplicationsRepository]

  }

  trait SandboxJobSetup extends Setup {
    val deleteUnusedApplicationsAfter  = 365
    val notifyDeletionPendingInAdvance = 30
    val configuration                  = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForSandbox = Seq(notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedSandboxApplicationRecordsJob(
      mockSandboxThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      fixedClock,
      mockLockRepository
    )
  }

  trait ProductionJobSetup extends Setup {
    val deleteUnusedApplicationsAfter  = 365
    val notifyDeletionPendingInAdvance = 30
    val startDeletingOn                = LocalDate.now(fixedClock).plusDays(notifyDeletionPendingInAdvance)

    val configuration =
      jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = Seq(notifyDeletionPendingInAdvance), startDeletingOn = startDeletingOn)

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockProductionThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      fixedClock,
      mockLockRepository
    )
  }

  trait MultipleNotificationsSetup extends Setup {
    val deleteUnusedApplicationsAfter  = 365
    val notifyDeletionPendingInAdvance = Seq(30, 14, 7)
    val configuration                  = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = notifyDeletionPendingInAdvance)

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockProductionThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      fixedClock,
      mockLockRepository
    )
  }

  "notificationCutoffDate" should {
    "correctly calculate date to retrieve applications not used since" in new SandboxJobSetup {
      val daysDifference     = deleteUnusedApplicationsAfter - notifyDeletionPendingInAdvance
      val expectedCutoffDate = LocalDateTime.now(fixedClock).minusDays(daysDifference)

      val calculatedCutoffDate = underTest.notificationCutoffDate()

      calculatedCutoffDate.toInstant(utc).toEpochMilli shouldBe (expectedCutoffDate.toInstant(utc).toEpochMilli)
    }
  }

  "calculateNotificationDates" should {
    "correctly calculate when notifications should be sent" in new MultipleNotificationsSetup {
      val scheduledDeletionDate     = LocalDate.now(fixedClock).plusDays(40)
      val expectedNotificationDates = notifyDeletionPendingInAdvance.map(scheduledDeletionDate.minusDays(_))

      val calculatedNotificationDates = underTest.calculateNotificationDates(scheduledDeletionDate)

      calculatedNotificationDates should contain allElementsOf (expectedNotificationDates)
    }
  }

  "calculateScheduledDeletionDate" should {
    "correctly calculate date that Sandbox application should be deleted" in new SandboxJobSetup {
      val lastUseDate          = LocalDate.now(fixedClock)
      val expectedDeletionDate = lastUseDate.plusDays(deleteUnusedApplicationsAfter)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe expectedDeletionDate
    }

    "correctly calculate date that Sandbox application should be deleted when lastUseDate is now minus deleteUnusedApplicationsAfter+100" in new SandboxJobSetup {
      val lastUseDate            = LocalDate.now(fixedClock).minusDays(deleteUnusedApplicationsAfter).minusDays(100)
      val expectedDeletionDate   = lastUseDate.plusDays(deleteUnusedApplicationsAfter)
      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe expectedDeletionDate
    }

    "correctly calculate date that Production application should be deleted when lastUseDate is today" in new ProductionJobSetup {
      val lastUseDate          = LocalDate.now(fixedClock)
      val expectedDeletionDate = lastUseDate.plusDays(deleteUnusedApplicationsAfter)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe expectedDeletionDate
    }

    "correctly calculate date that Production application should be deleted when lastUseDate is 364 days before startDeletingOn" in new ProductionJobSetup {
      val lastUseDate          = startDeletingOn.minusDays(deleteUnusedApplicationsAfter - 1)
      val expectedDeletionDate = lastUseDate.plusDays(deleteUnusedApplicationsAfter)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe expectedDeletionDate
    }

    "correctly calculate date that Production application should be deleted when lastUseDate is a year before startDeletingOn" in new ProductionJobSetup {
      val lastUseDate = startDeletingOn.minusDays(deleteUnusedApplicationsAfter)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe startDeletingOn
    }

    "correctly calculate date that Production application should be deleted when lastUseDate is now minus deleteUnusedApplicationsAfter" in new ProductionJobSetup {
      val lastUseDate = LocalDate.now(fixedClock).minusDays(deleteUnusedApplicationsAfter)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe startDeletingOn
    }

    "correctly calculate date that Production application should be deleted when lastUseDate is now minus deleteUnusedApplicationsAfter+1" in new ProductionJobSetup {
      val lastUseDate = LocalDate.now(fixedClock).minusDays(deleteUnusedApplicationsAfter).minusDays(1)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe startDeletingOn
    }

    "correctly calculate date that Production application should be deleted when lastUseDate is now minus deleteUnusedApplicationsAfter+100" in new ProductionJobSetup {
      val lastUseDate = LocalDate.now(fixedClock).minusDays(deleteUnusedApplicationsAfter).minusDays(100)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe startDeletingOn
    }
  }
//
  "SANDBOX job" should {
    "add newly discovered unused applications with last used dates to database" in new SandboxJobSetup {
      val adminUserEmail                                                           = "foo@bar.com".toLaxEmail
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environments.SANDBOX, LocalDateTime.now(fixedClock).minusMonths(13), Some(LocalDateTime.now(fixedClock).minusMonths(13)), Set(adminUserEmail))

      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(*, *))
        .thenReturn(successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environments.SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())

      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environments.SANDBOX)

      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "add newly discovered unused applications with no last used dates to database" in new SandboxJobSetup {
      val adminUserEmail                                                              = "foo@bar.com".toLaxEmail
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(SANDBOX, LocalDateTime.now(fixedClock).minusMonths(13), None, Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(*, *)).thenReturn(successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(*)).thenReturn(Future.successful(true))
      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())
      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (SANDBOX)

      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "not persist application details already stored in database" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environments.SANDBOX,
          LocalDateTime.now(fixedClock).minusMonths(13),
          Some(LocalDateTime.now(fixedClock).minusMonths(13)),
          Set()
        ) // scalastyle:off magic.number

      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(*, *))
        .thenReturn(successful(List(application._1)))
      when(mockUnusedApplicationsRepository.unusedApplications(SANDBOX)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "remove applications that have been updated since last run" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environments.SANDBOX,
          LocalDateTime.now(fixedClock).minusMonths(13),
          Some(LocalDateTime.now(fixedClock).minusMonths(13)),
          Set()
        ) // scalastyle:off magic.number

      when(mockSandboxThirdPartyApplicationConnector.applicationSearch(*, *)).thenReturn(successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplications(SANDBOX)).thenReturn(Future(List(application._2)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(eqTo(SANDBOX), *[ApplicationId])).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(SANDBOX, application._2.applicationId)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

  }
//
  "PRODUCTION job" should {
    "add newly discovered unused applications with last used dates to database" in new ProductionJobSetup {
      val adminUserEmail                                                           = "foo@bar.com".toLaxEmail
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environments.PRODUCTION,
          LocalDateTime.now(fixedClock).minusMonths(13),
          Some(LocalDateTime.now(fixedClock).minusMonths(13)),
          Set(adminUserEmail)
        ) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationSearch(*, *))
        .thenReturn(successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])

      await(underTest.runJob)
      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())
      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (PRODUCTION)

      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "add newly discovered unused applications with no last used dates to database" in new ProductionJobSetup {
      val adminUserEmail                                                              = "foo@bar.com".toLaxEmail
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environments.PRODUCTION, LocalDateTime.now(fixedClock).minusMonths(13), None, Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationSearch(*, *)).thenReturn(successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])

      await(underTest.runJob)
      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())
      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environments.PRODUCTION)

      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "not persist application details already stored in database" in new ProductionJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environments.PRODUCTION,
          LocalDateTime.now(fixedClock).minusMonths(13),
          Some(LocalDateTime.now(fixedClock).minusMonths(13)),
          Set()
        ) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationSearch(*, *))
        .thenReturn(successful(List(application._1)))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      // verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "remove applications that have been updated since last run" in new ProductionJobSetup {

      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environments.PRODUCTION,
          LocalDateTime.now(fixedClock).minusMonths(13),
          Some(LocalDateTime.now(fixedClock).minusMonths(13)),
          Set()
        ) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationSearch(*, *)).thenReturn(successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplications(eqTo(PRODUCTION))).thenReturn(Future(List(application._2)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(eqTo(PRODUCTION), *[ApplicationId])).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(PRODUCTION), eqTo(application._2.applicationId))

      // verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }
  }

  private def applicationDetails(
      environment: Environment,
      creationDate: LocalDateTime,
      lastAccessDate: Option[LocalDateTime],
      administrators: Set[LaxEmailAddress]
    ): (ApplicationUsageDetails, UnusedApplication) = {
    val applicationId        = ApplicationId.random
    val applicationName      = Random.alphanumeric.take(10).mkString
    val administratorDetails = administrators.map(admin => new Administrator(admin, "Foo", "Bar"))
    val lastInteractionDate  = lastAccessDate.getOrElse(creationDate).toLocalDate

    (
      ApplicationUsageDetails(applicationId, applicationName, administrators, creationDate, lastAccessDate),
      UnusedApplication(
        applicationId,
        applicationName,
        administratorDetails.toSeq,
        environment,
        lastInteractionDate,
        List(lastInteractionDate.plusDays(335)),
        lastInteractionDate.plusDays(365)
      )
    )
  }
}
