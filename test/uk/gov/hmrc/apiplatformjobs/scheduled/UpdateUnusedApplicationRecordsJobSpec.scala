/*
 * Copyright 2020 HM Revenue & Customs
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

import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.{DateTime, DateTimeUtils}
import org.mockito.Mockito.{times, verify, verifyNoInteractions, when}
import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar}
import org.scalatest.Matchers._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.commands.MultiBulkWriteResult
import uk.gov.hmrc.apiplatformjobs.connectors.{EmailConnector, ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

class UpdateUnusedApplicationRecordsJobSpec extends PlaySpec
  with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with FutureAwaits with DefaultAwaitTimeout {

  val BaselineTime = DateTime.now.getMillis
  DateTimeUtils.setCurrentMillisFixed(BaselineTime)

  trait Setup {
    val environmentName = "Test Environment"

    def jobConfiguration(deleteUnusedApplicationsAfter: Int = 365,
                         notifyDeletionPendingInAdvanceForSandbox: Int = 30,
                         notifyDeletionPendingInAdvanceForProduction: Int = 30): Config = {
      ConfigFactory.parseString(
        s"""
           |deleteUnusedApplicationsAfter = ${deleteUnusedApplicationsAfter}d
           |
           |UpdateUnusedApplicationRecords-SANDBOX {
           |  startTime = "00:30"
           |  executionInterval = 1d
           |  enabled = false
           |  externalEnvironmentName = "External Test"
           |  notifyDeletionPendingInAdvance = ${notifyDeletionPendingInAdvanceForSandbox}d
           |}
           |
           |UpdateUnusedApplicationRecords-PRODUCTION {
           |  startTime = "01:00"
           |  executionInterval = 1d
           |  enabled = false
           |  externalEnvironmentName = "Live"
           |  notifyDeletionPendingInAdvance = ${notifyDeletionPendingInAdvanceForProduction}d
           |}
           |
           |""".stripMargin)
    }

    val mockSandboxThirdPartyApplicationConnector: SandboxThirdPartyApplicationConnector = mock[SandboxThirdPartyApplicationConnector]
    val mockProductionThirdPartyApplicationConnector: ProductionThirdPartyApplicationConnector = mock[ProductionThirdPartyApplicationConnector]
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector = mock[ThirdPartyDeveloperConnector]
    val mockEmailConnector: EmailConnector = mock[EmailConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]

    val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }
  }

  trait SandboxJobSetup extends Setup {
    val deleteUnusedApplicationsAfter = 365
    val notifyDeletionPendingInAdvance = 30
    val configuration = new Configuration(jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedSandboxApplicationRecordsJob(
      mockSandboxThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockEmailConnector,
      mockUnusedApplicationsRepository,
      configuration,
      reactiveMongoComponent
    )
  }

  trait ProductionJobSetup extends Setup {
    val deleteUnusedApplicationsAfter = 365
    val notifyDeletionPendingInAdvance = 30
    val configuration =
      new Configuration(jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockProductionThirdPartyApplicationConnector,
      mockThirdPartyDeveloperConnector,
      mockEmailConnector,
      mockUnusedApplicationsRepository,
      configuration,
      reactiveMongoComponent
    )
  }

  "notificationCutoffDate" should {
    "correctly calculate date to retrieve applications not used since" in new SandboxJobSetup {
      val daysDifference = deleteUnusedApplicationsAfter - notifyDeletionPendingInAdvance
      val expectedCutoffDate = DateTime.now.minusDays(daysDifference)

      val calculatedCutoffDate = underTest.notificationCutoffDate()

      calculatedCutoffDate.getMillis must be (expectedCutoffDate.getMillis)
    }
  }

  "calculateScheduledDeletionDate" should {
    "correctly calculate date that application should be deleted" in new SandboxJobSetup {
      val lastUseDate = DateTime.now()
      val expectedDeletionDate = lastUseDate.plusDays(deleteUnusedApplicationsAfter)

      val calculatedDeletionDate = underTest.calculateScheduledDeletionDate(lastUseDate)

      calculatedDeletionDate.getMillis must be (expectedDeletionDate.getMillis)
    }
  }

  "SANDBOX job" should {
    "add newly discovered unused applications with last used dates to database" in new SandboxJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, DateTime.now.minusMonths(13), Some(DateTime.now.minusMonths(13)), Set(adminUserEmail))

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(Future.successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(Future.successful(Seq((adminUserEmail, "Foo", "Bar"))))
      when(mockUnusedApplicationsRepository.applicationsByEnvironment(Environment.SANDBOX)).thenReturn(Future(List.empty))

      val emailCaptor: ArgumentCaptor[UnusedApplicationToBeDeletedNotification] = ArgumentCaptor.forClass(classOf[UnusedApplicationToBeDeletedNotification])
      when(mockEmailConnector.sendApplicationToBeDeletedNotification(emailCaptor.capture())).thenReturn(Future.successful(true))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(insertCaptor.capture())(*)).thenReturn(Future.successful(MultiBulkWriteResult.empty))

      await(underTest.runJob)

      emailCaptor.getAllValues.size() must be (1)
      val capturedEmail = emailCaptor.getValue
      capturedEmail.applicationName must be (applicationWithLastUseDate._1.applicationName)
      capturedEmail.userEmailAddress must be (adminUserEmail)
      capturedEmail.userFirstName must be ("Foo")
      capturedEmail.userLastName must be ("Bar")
      capturedEmail.timeBeforeDeletion must be (s"$deleteUnusedApplicationsAfter days")
      capturedEmail.environmentName must be ("External Test")

      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size must be (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId must be (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName must be (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment must be (Environment.SANDBOX)

      verifyNoInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "add newly discovered unused applications with no last used dates to database" in new SandboxJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, DateTime.now.minusMonths(13), None, Set(adminUserEmail))

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*)).thenReturn(Future.successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(Future.successful(Seq((adminUserEmail, "Foo", "Bar"))))
      when(mockUnusedApplicationsRepository.applicationsByEnvironment(Environment.SANDBOX)).thenReturn(Future(List.empty))

      val emailCaptor: ArgumentCaptor[UnusedApplicationToBeDeletedNotification] = ArgumentCaptor.forClass(classOf[UnusedApplicationToBeDeletedNotification])
      when(mockEmailConnector.sendApplicationToBeDeletedNotification(emailCaptor.capture())).thenReturn(Future.successful(true))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(insertCaptor.capture())(*)).thenReturn(Future.successful(MultiBulkWriteResult.empty))

      await(underTest.runJob)

      emailCaptor.getAllValues.size() must be (1)
      val capturedEmail = emailCaptor.getValue
      capturedEmail.applicationName must be (applicationWithoutLastUseDate._1.applicationName)
      capturedEmail.userEmailAddress must be (adminUserEmail)
      capturedEmail.userFirstName must be ("Foo")
      capturedEmail.userLastName must be ("Bar")
      capturedEmail.timeBeforeDeletion must be (s"$deleteUnusedApplicationsAfter days")
      capturedEmail.environmentName must be ("External Test")


      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size must be (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId must be (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName must be (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment must be (Environment.SANDBOX)

      verifyNoInteractions(mockProductionThirdPartyApplicationConnector)
    }

    "not persist application details already stored in database" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, DateTime.now.minusMonths(13), Some(DateTime.now.minusMonths(13)), Set())

      when(mockSandboxThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(Future.successful(List(application._1)))
      when(mockUnusedApplicationsRepository.applicationsByEnvironment(Environment.SANDBOX)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository, times(0)).bulkInsert(*)(*)
      verifyNoInteractions(mockThirdPartyDeveloperConnector)
      verifyNoInteractions(mockProductionThirdPartyApplicationConnector)
    }

  }

  "PRODUCTION job" should {
    "add newly discovered unused applications with last used dates to database" in new ProductionJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, DateTime.now.minusMonths(13), Some(DateTime.now.minusMonths(13)), Set(adminUserEmail))

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(Future.successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(Future.successful(Seq((adminUserEmail, "Foo", "Bar"))))
      when(mockUnusedApplicationsRepository.applicationsByEnvironment(Environment.PRODUCTION)).thenReturn(Future(List.empty))

      val emailCaptor: ArgumentCaptor[UnusedApplicationToBeDeletedNotification] = ArgumentCaptor.forClass(classOf[UnusedApplicationToBeDeletedNotification])
      when(mockEmailConnector.sendApplicationToBeDeletedNotification(emailCaptor.capture())).thenReturn(Future.successful(true))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(insertCaptor.capture())(*)).thenReturn(Future.successful(MultiBulkWriteResult.empty))

      await(underTest.runJob)

      emailCaptor.getAllValues.size() must be (1)
      val capturedEmail = emailCaptor.getValue
      capturedEmail.applicationName must be (applicationWithLastUseDate._1.applicationName)
      capturedEmail.userEmailAddress must be (adminUserEmail)
      capturedEmail.userFirstName must be ("Foo")
      capturedEmail.userLastName must be ("Bar")
      capturedEmail.timeBeforeDeletion must be (s"$deleteUnusedApplicationsAfter days")
      capturedEmail.environmentName must be ("Live")

      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size must be (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId must be (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName must be (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment must be (Environment.PRODUCTION)

      verifyNoInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "add newly discovered unused applications with no last used dates to database" in new ProductionJobSetup {
      val adminUserEmail = "foo@bar.com"
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, DateTime.now.minusMonths(13), None, Set(adminUserEmail))

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*)).thenReturn(Future.successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail))).thenReturn(Future.successful(Seq((adminUserEmail, "Foo", "Bar"))))
      when(mockUnusedApplicationsRepository.applicationsByEnvironment(Environment.PRODUCTION)).thenReturn(Future(List.empty))

      val emailCaptor: ArgumentCaptor[UnusedApplicationToBeDeletedNotification] = ArgumentCaptor.forClass(classOf[UnusedApplicationToBeDeletedNotification])
      when(mockEmailConnector.sendApplicationToBeDeletedNotification(emailCaptor.capture())).thenReturn(Future.successful(true))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(insertCaptor.capture())(*)).thenReturn(Future.successful(MultiBulkWriteResult.empty))

      await(underTest.runJob)

      emailCaptor.getAllValues.size() must be (1)
      val capturedEmail = emailCaptor.getValue
      capturedEmail.applicationName must be (applicationWithoutLastUseDate._1.applicationName)
      capturedEmail.userEmailAddress must be (adminUserEmail)
      capturedEmail.userFirstName must be ("Foo")
      capturedEmail.userLastName must be ("Bar")
      capturedEmail.timeBeforeDeletion must be (s"$deleteUnusedApplicationsAfter days")
      capturedEmail.environmentName must be ("Live")


      val capturedInsertValue = insertCaptor.getValue
      capturedInsertValue.size must be (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId must be (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName must be (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment must be (Environment.PRODUCTION)

      verifyNoInteractions(mockSandboxThirdPartyApplicationConnector)
    }

    "not persist application details already stored in database" in new ProductionJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, DateTime.now.minusMonths(13), Some(DateTime.now.minusMonths(13)), Set())

      when(mockProductionThirdPartyApplicationConnector.applicationsLastUsedBefore(*))
        .thenReturn(Future.successful(List(application._1)))
      when(mockUnusedApplicationsRepository.applicationsByEnvironment(Environment.PRODUCTION)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository, times(0)).bulkInsert(*)(*)
      verifyNoInteractions(mockThirdPartyDeveloperConnector)
      verifyNoInteractions(mockSandboxThirdPartyApplicationConnector)
    }

  }

  private def applicationDetails(environment: Environment,
                         creationDate: DateTime,
                         lastAccessDate: Option[DateTime],
                         administrators: Set[String]): (ApplicationUsageDetails, UnusedApplication) = {
    val applicationId = UUID.randomUUID()
    val applicationName = Random.alphanumeric.take(10).mkString
    val administratorDetails = administrators.map(admin => new AdministratorNotification(admin, "Foo", "Bar", Some(DateTime.now)))
    val lastInteractionDate = lastAccessDate.getOrElse(creationDate)

    (ApplicationUsageDetails(applicationId, applicationName, administrators, creationDate, lastAccessDate),
      UnusedApplication(applicationId, applicationName, administratorDetails.toSeq, environment, lastInteractionDate, lastInteractionDate.plusDays(365)))
  }
}
