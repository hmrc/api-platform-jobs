/*
 * Copyright 2022 HM Revenue & Customs
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

import com.mongodb.bulk.BulkWriteResult
import org.mockito.ArgumentCaptor
import org.mockito.MockitoSugar.verifyZeroInteractions
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.DeveloperResponse
import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.Environment.{Environment, PRODUCTION, SANDBOX}
import uk.gov.hmrc.apiplatformjobs.models.{Environment, _}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.Random

class UpdateUnusedApplicationRecordsJobSpec extends AsyncHmrcSpec with UnusedApplicationTestConfiguration {

  trait Setup extends BaseSetup {
    val environmentName = "Test Environment"

    val mockSandboxThirdPartyApplicationConnector: SandboxThirdPartyApplicationConnector = mock[SandboxThirdPartyApplicationConnector]
    val mockProductionThirdPartyApplicationConnector: ProductionThirdPartyApplicationConnector = mock[ProductionThirdPartyApplicationConnector]
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector = mock[ThirdPartyDeveloperConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]

  }

  trait SandboxJobSetup extends Setup {
    val deleteUnusedApplicationsAfter = 365
    val notifyDeletionPendingInAdvance = 30
    val configuration = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForSandbox = Seq(notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedSandboxApplicationRecordsJob(
      mockSandboxThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      mockLockRepository
    )
  }

  trait ProductionJobSetup extends Setup {
    val deleteUnusedApplicationsAfter = 365
    val notifyDeletionPendingInAdvance = 30
    val configuration = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = Seq(notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockProductionThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      mockLockRepository
    )
  }

  trait MultipleNotificationsSetup extends Setup {
    val deleteUnusedApplicationsAfter = 365
    val notifyDeletionPendingInAdvance = Seq(30, 14 ,7)
    val configuration = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = notifyDeletionPendingInAdvance)

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockProductionThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      mockLockRepository
    )
  }

  "notificationCutoffDate" should {
    "correctly calculate date to retrieve applications not used since" in new SandboxJobSetup {
      val daysDifference = deleteUnusedApplicationsAfter - notifyDeletionPendingInAdvance
      val expectedCutoffDate = LocalDateTime.now(utc).minusDays(daysDifference)

      val calculatedCutoffDate = underTest.notificationCutoffDate()

      calculatedCutoffDate.toInstant(utc).toEpochMilli shouldBe (expectedCutoffDate.toInstant(utc).toEpochMilli)
    }
  }

  "calculateNotificationDates" should {
    "correctly calculate when notifications should be sent" in new MultipleNotificationsSetup {
      val scheduledDeletionDate = LocalDate.now.plusDays(40)
      val expectedNotificationDates = notifyDeletionPendingInAdvance.map(scheduledDeletionDate.minusDays(_))

      val calculatedNotificationDates = underTest.calculateNotificationDates(scheduledDeletionDate)

      calculatedNotificationDates should contain allElementsOf (expectedNotificationDates)
    }
  }

  "calculateScheduledDeletionDate" should {
    "correctly calculate date that application should be deleted" in new SandboxJobSetup {
      val lastUseDate = LocalDateTime.now(utc)
      val expectedDeletionDate = lastUseDate.plusDays(deleteUnusedApplicationsAfter).toLocalDate

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate shouldBe (expectedDeletionDate)
    }
  }

  "SANDBOX job" should {
    "add newly discovered unused applications with last used dates to database" in new SandboxJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, LocalDateTime.now(utc).minusMonths(13), Some(LocalDateTime.now(utc).minusMonths(13)), Set(adminUserEmail))

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.collection.bulkWrite(*).toFuture()).thenReturn(successful(BulkWriteResult.unacknowledged()))

      await(underTest.runJob)

      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.SANDBOX)

      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "add newly discovered unused applications with no last used dates to database" in new SandboxJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(SANDBOX, LocalDateTime.now.minusMonths(13), None, Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*)).thenReturn(successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.collection.bulkWrite(*).toFuture()).thenReturn(successful(BulkWriteResult.unacknowledged()))
      await(underTest.runJob)

      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (SANDBOX)

      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "not persist application details already stored in database" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, LocalDateTime.now.minusMonths(13), Some(LocalDateTime.now.minusMonths(13)), Set()) // scalastyle:off magic.number

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(successful(List(application._1)))
      when(mockUnusedApplicationsRepository.unusedApplications(SANDBOX)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "remove applications that have been updated since last run" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, LocalDateTime.now.minusMonths(13), Some(LocalDateTime.now.minusMonths(13)), Set()) // scalastyle:off magic.number

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*)).thenReturn(successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplications(SANDBOX)).thenReturn(Future(List(application._2)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(eqTo(SANDBOX), *)).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(SANDBOX, application._2.applicationId)

      verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockProductionThirdPartyApplicationConnector)
    }

  }

  "PRODUCTION job" should {
    "add newly discovered unused applications with last used dates to database" in new ProductionJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, LocalDateTime.now.minusMonths(13), Some(LocalDateTime.now.minusMonths(13)), Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.collection.bulkWrite(*).toFuture()).thenReturn(successful(BulkWriteResult.unacknowledged()))

      await(underTest.runJob)

      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (PRODUCTION)

      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "add newly discovered unused applications with no last used dates to database" in new ProductionJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, LocalDateTime.now.minusMonths(13), None, Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*)).thenReturn(successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.collection.bulkWrite(*).toFuture()).thenReturn(successful(BulkWriteResult.unacknowledged()))

      await(underTest.runJob)

      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.PRODUCTION)

      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "not persist application details already stored in database" in new ProductionJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, LocalDateTime.now.minusMonths(13), Some(LocalDateTime.now.minusMonths(13)), Set()) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(successful(List(application._1)))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "remove applications that have been updated since last run" in new ProductionJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, LocalDateTime.now.minusMonths(13), Some(LocalDateTime.now.minusMonths(13)), Set()) // scalastyle:off magic.number

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*)).thenReturn(successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplications(PRODUCTION)).thenReturn(Future(List(application._2)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(eqTo(PRODUCTION), *)).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(PRODUCTION, application._2.applicationId)

      verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
      verifyZeroInteractions(mockSandboxThirdPartyApplicationConnector)
    }
  }

  private def applicationDetails(environment: Environment,
                                 creationDate: LocalDateTime,
                                 lastAccessDate: Option[LocalDateTime],
                                 administrators: Set[String]): (ApplicationUsageDetails, UnusedApplication) = {
    val applicationId = UUID.randomUUID()
    val applicationName = Random.alphanumeric.take(10).mkString
    val administratorDetails = administrators.map(admin => new Administrator(admin, "Foo", "Bar"))
    val lastInteractionDate = lastAccessDate.getOrElse(creationDate)

    (ApplicationUsageDetails(applicationId, applicationName, administrators, creationDate, lastAccessDate),
      UnusedApplication(applicationId, applicationName, administratorDetails.toSeq, environment, lastInteractionDate, List(lastInteractionDate.plusDays(335).toLocalDate), lastInteractionDate.plusDays(365).toLocalDate))
  }
}
