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

package uk.gov.hmrc.apiplatformjobs.repository

import akka.stream.testkit.NoMaterializer
import org.joda.time.{DateTime, LocalDate}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.apiplatformjobs.models.Environment._
import uk.gov.hmrc.apiplatformjobs.models.UnusedApplication
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class UnusedApplicationsRepositorySpec extends AsyncHmrcSpec
  with MongoSpecSupport
  with BeforeAndAfterEach with BeforeAndAfterAll
  with IndexVerification {

  implicit val materializer = NoMaterializer

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val unusedApplicationRepository = new UnusedApplicationsRepository(reactiveMongoComponent)

  override def beforeEach() {
    super.beforeEach()
    await(unusedApplicationRepository.drop)
    await(unusedApplicationRepository.ensureIndexes)
  }

  override protected def afterAll() {
    super.afterAll()
    await(unusedApplicationRepository.drop)
  }

  trait Setup {
    def sandboxApplication(applicationId: UUID,
                           lastInteractionDate: DateTime = DateTime.now,
                           scheduledNotificationDates: Seq[LocalDate] = List(LocalDate.now.plusDays(1)),
                           scheduledDeletionDate: LocalDate = LocalDate.now.plusDays(30)) =
      UnusedApplication(
        applicationId,
        Random.alphanumeric.take(10).mkString,
        Seq(),
        SANDBOX,
        lastInteractionDate,
        scheduledNotificationDates,
        scheduledDeletionDate)
    def productionApplication(applicationId: UUID,
                              lastInteractionDate: DateTime = DateTime.now,
                              scheduledNotificationDates: Seq[LocalDate] = List(LocalDate.now.plusDays(1)),
                              scheduledDeletionDate: LocalDate = LocalDate.now.plusDays(30)) =
      UnusedApplication(
        applicationId,
        Random.alphanumeric.take(10).mkString,
        Seq(),
        PRODUCTION,
        lastInteractionDate,
        scheduledNotificationDates,
        scheduledDeletionDate)
  }

  "The 'unusedApplications' collection" should {
    "have all the current indexes" in {

      val expectedIndexes = Set(
        Index(key = List("environment" -> Ascending, "applicationId" -> Ascending), name = Some("applicationIdIndex"), unique = true, background = true),
        Index(key = List("environment" -> Ascending, "scheduledNotificationDates" -> Ascending), name = Some("scheduledNotificationDatesIndex"), unique = false, background = true),
        Index(key = List("environment" -> Ascending, "scheduledDeletionDate" -> Ascending), name = Some("scheduledDeletionDateIndex"), unique = false, background = true)
      )

      verifyIndexesVersionAgnostic(unusedApplicationRepository, expectedIndexes)
    }
  }

  "applicationsByEnvironment" should {
    "correctly retrieve SANDBOX applications" in new Setup {
      val sandboxApplicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(sandboxApplicationId),
            productionApplication(UUID.randomUUID()),
            productionApplication(UUID.randomUUID()))))

      val results = await(unusedApplicationRepository.unusedApplications(SANDBOX))

      results.size should be (1)
      results.head.applicationId should be (sandboxApplicationId)
    }

    "correctly retrieve PRODUCTION applications" in new Setup {
      val productionApplication1Id = UUID.randomUUID
      val productionApplication2Id = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(UUID.randomUUID()),
            productionApplication(productionApplication1Id),
            productionApplication(productionApplication2Id))))

      val results = await(unusedApplicationRepository.unusedApplications(PRODUCTION))

      results.size should be (2)
      val returnedApplicationIds = results.map(_.applicationId)
      returnedApplicationIds should contain (productionApplication1Id)
      returnedApplicationIds should contain (productionApplication2Id)
    }
  }

  "unusedApplicationsToBeNotified" should {
    "retrieve SANDBOX application that is due a notification being sent" in new Setup {
      val applicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(1))),
            sandboxApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
            productionApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))))))

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(SANDBOX))

      results.size should be (1)
      results.head.applicationId should be (applicationId)
    }

    "retrieve SANDBOX application that is due multiple notifications being sent only once" in new Setup {
      val applicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1))),
            sandboxApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
            productionApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))))))

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(SANDBOX))

      results.size should be (1)
      results.head.applicationId should be (applicationId)
    }

    "retrieve PRODUCTION application that is due a notification being sent" in new Setup {
      val applicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            productionApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(1))),
            productionApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
            sandboxApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))))))

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(PRODUCTION))

      results.size should be (1)
      results.head.applicationId should be (applicationId)
    }

    "retrieve PRODUCTION application that is due multiple notifications being sent only once" in new Setup {
      val applicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            productionApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1))),
            productionApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
            sandboxApplication(UUID.randomUUID, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))))))

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(PRODUCTION))

      results.size should be (1)
      results.head.applicationId should be (applicationId)
    }
  }

  "updateNotificationsSent" should {
    "remove all scheduled notifications for SANDBOX apps prior to a specific date" in new Setup {
      val applicationId = UUID.randomUUID
      await(unusedApplicationRepository.insert(
        sandboxApplication(
          applicationId,
          scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1), LocalDate.now.plusDays(1)))))

      val result = await(unusedApplicationRepository.updateNotificationsSent(SANDBOX, applicationId))

      result should be (true)
      val updatedApplication: UnusedApplication = await(unusedApplicationRepository.find("applicationId" -> applicationId)).head
      updatedApplication.scheduledNotificationDates.size should be (1)
      updatedApplication.scheduledNotificationDates.head.isAfter(LocalDate.now) should be (true)
    }

    "remove all scheduled notifications for PRODUCTION apps prior to a specific date" in new Setup {
      val applicationId = UUID.randomUUID
      await(unusedApplicationRepository.insert(
        productionApplication(
          applicationId,
          scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1), LocalDate.now.plusDays(1)))))

      val result = await(unusedApplicationRepository.updateNotificationsSent(PRODUCTION, applicationId))

      result should be (true)
      val updatedApplication: UnusedApplication = await(unusedApplicationRepository.find("applicationId" -> applicationId)).head
      updatedApplication.scheduledNotificationDates.size should be (1)
      updatedApplication.scheduledNotificationDates.head.isAfter(LocalDate.now) should be (true)
    }
  }

  "applicationsToBeDeleted" should {
    "correctly retrieve SANDBOX applications that are scheduled to be deleted" in new Setup {
      val sandboxApplicationToBeDeleted: UnusedApplication = sandboxApplication(UUID.randomUUID, scheduledDeletionDate = LocalDate.now.minusDays(1))
      val sandboxApplicationToNotBeDeleted: UnusedApplication = sandboxApplication(UUID.randomUUID, scheduledDeletionDate = LocalDate.now.plusDays(1))

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplicationToBeDeleted,
            sandboxApplicationToNotBeDeleted,
            productionApplication(UUID.randomUUID()),
            productionApplication(UUID.randomUUID()))))

      val results = await(unusedApplicationRepository.unusedApplicationsToBeDeleted(SANDBOX, DateTime.now))

      val returnedApplicationIds = results.map(_.applicationId)
      returnedApplicationIds.size should be (1)
      returnedApplicationIds.head should be (sandboxApplicationToBeDeleted.applicationId)
    }

    "correctly retrieve PRODUCTION applications that are scheduled to be deleted" in new Setup {
      val productionApplicationToBeDeleted: UnusedApplication = productionApplication(UUID.randomUUID, scheduledDeletionDate = LocalDate.now.minusDays(1))
      val productionApplicationToNotBeDeleted: UnusedApplication = productionApplication(UUID.randomUUID, scheduledDeletionDate = LocalDate.now.plusDays(1))

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(UUID.randomUUID()),
            sandboxApplication(UUID.randomUUID()),
            productionApplicationToBeDeleted,
            productionApplicationToNotBeDeleted)))

      val results = await(unusedApplicationRepository.unusedApplicationsToBeDeleted(PRODUCTION, DateTime.now))

      val returnedApplicationIds = results.map(_.applicationId)
      returnedApplicationIds.size should be (1)
      returnedApplicationIds.head should be (productionApplicationToBeDeleted.applicationId)
    }
  }

  "deleteApplication" should {
    "correctly remove a SANDBOX application" in new Setup {
      val sandboxApplicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(sandboxApplicationId),
            productionApplication(UUID.randomUUID()),
            productionApplication(UUID.randomUUID()))))

      val result = await(unusedApplicationRepository.deleteUnusedApplicationRecord(SANDBOX, sandboxApplicationId))

      result should be (true)
      await(unusedApplicationRepository.unusedApplications(SANDBOX)).size should be (0)
    }

    "correctly remove a PRODUCTION application" in new Setup {
      val productionApplicationId = UUID.randomUUID

      await(unusedApplicationRepository
        .bulkInsert(
          Seq(
            sandboxApplication(UUID.randomUUID()),
            productionApplication(productionApplicationId))))

      val result = await(unusedApplicationRepository.deleteUnusedApplicationRecord(PRODUCTION, productionApplicationId))

      result should be (true)
      await(unusedApplicationRepository.unusedApplications(PRODUCTION)).size should be (0)
    }

    "return true if application id was not found in database" in {
      val result = await(unusedApplicationRepository.deleteUnusedApplicationRecord(PRODUCTION, UUID.randomUUID()))

      result should be (true)
    }
  }
}
