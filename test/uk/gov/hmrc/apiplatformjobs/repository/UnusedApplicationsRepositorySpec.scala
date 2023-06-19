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

package uk.gov.hmrc.apiplatformjobs.repository

import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import akka.stream.testkit.NoMaterializer
import org.mongodb.scala.Document
import org.mongodb.scala.model.Filters
import org.mongodb.scala.result.InsertOneResult
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

import uk.gov.hmrc.apiplatformjobs.models.Environments._
import uk.gov.hmrc.apiplatformjobs.models.{Environments, UnusedApplication}
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class UnusedApplicationsRepositorySpec
    extends AsyncHmrcSpec
    with GuiceOneAppPerSuite
    with DefaultPlayMongoRepositorySupport[UnusedApplication]
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  implicit val materializer = NoMaterializer

  private val unusedApplicationRepository = new UnusedApplicationsRepository(mongoComponent, fixedClock)

  override protected val repository: PlayMongoRepository[UnusedApplication] = unusedApplicationRepository

  trait Setup {

    def sandboxApplication(
        applicationId: ApplicationId,
        lastInteractionDate: LocalDate = LocalDate.now(fixedClock),
        scheduledNotificationDates: Seq[LocalDate] = List(LocalDate.now(fixedClock).plusDays(1)),
        scheduledDeletionDate: LocalDate = LocalDate.now(fixedClock).plusDays(30)
      ) =
      UnusedApplication(applicationId, Random.alphanumeric.take(10).mkString, Seq(), SANDBOX, lastInteractionDate, scheduledNotificationDates, scheduledDeletionDate)

    def productionApplication(
        applicationId: ApplicationId,
        lastInteractionDate: LocalDate = LocalDate.now(fixedClock),
        scheduledNotificationDates: Seq[LocalDate] = List(LocalDate.now(fixedClock).plusDays(1)),
        scheduledDeletionDate: LocalDate = LocalDate.now(fixedClock).plusDays(30)
      ) =
      UnusedApplication(applicationId, Random.alphanumeric.take(10).mkString, Seq(), PRODUCTION, lastInteractionDate, scheduledNotificationDates, scheduledDeletionDate)
  }

  "applicationsByEnvironment" should {
    "correctly retrieve SANDBOX applications" in new Setup {
      val sandboxApplicationId = ApplicationId.random

      await(
        unusedApplicationRepository.collection
          .insertMany(Seq(sandboxApplication(sandboxApplicationId), productionApplication(ApplicationId.random), productionApplication(ApplicationId.random)))
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplications(SANDBOX))

      results.size should be(1)
      results.head.applicationId should be(sandboxApplicationId)
    }

    "correctly retrieve PRODUCTION applications" in new Setup {
      val productionApplication1Id = ApplicationId.random
      val productionApplication2Id = ApplicationId.random
      await(
        unusedApplicationRepository.collection
          .insertMany(Seq(sandboxApplication(ApplicationId.random), productionApplication(productionApplication1Id), productionApplication(productionApplication2Id)))
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplications(PRODUCTION))

      results.size should be(2)
      val returnedApplicationIds = results.map(_.applicationId)
      returnedApplicationIds should contain(productionApplication1Id)
      returnedApplicationIds should contain(productionApplication2Id)
    }
  }

  "unusedApplicationsToBeNotified" should {
    "retrieve SANDBOX application that is due a notification being sent" in new Setup {
      val applicationId = ApplicationId.random
      await(
        unusedApplicationRepository.collection
          .insertMany(
            Seq(
              sandboxApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(1))),
              sandboxApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
              productionApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1)))
            )
          )
          .toFuture()
      )
      val results       = await(unusedApplicationRepository.unusedApplicationsToBeNotified(SANDBOX))

      results.size should be(1)
      results.head.applicationId should be(applicationId)
    }

    "retrieve SANDBOX application that is due multiple notifications being sent only once" in new Setup {
      val applicationId = ApplicationId.random

      await(
        unusedApplicationRepository.collection
          .insertMany(
            Seq(
              sandboxApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1))),
              sandboxApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
              productionApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1)))
            )
          )
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(SANDBOX))

      results.size should be(1)
      results.head.applicationId should be(applicationId)
    }

    "retrieve PRODUCTION application that is due a notification being sent" in new Setup {
      val applicationId = ApplicationId.random

      await(
        unusedApplicationRepository.collection
          .insertMany(
            Seq(
              productionApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(1))),
              productionApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
              sandboxApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1)))
            )
          )
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(PRODUCTION))

      results.size should be(1)
      results.head.applicationId should be(applicationId)
    }

    "retrieve PRODUCTION application that is due multiple notifications being sent only once" in new Setup {
      val applicationId = ApplicationId.random

      await(
        unusedApplicationRepository.collection
          .insertMany(
            Seq(
              productionApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1))),
              productionApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1))),
              sandboxApplication(ApplicationId.random, scheduledNotificationDates = Seq(LocalDate.now.plusDays(1)))
            )
          )
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplicationsToBeNotified(PRODUCTION))

      results.size should be(1)
      results.head.applicationId should be(applicationId)
    }
  }

  "updateNotificationsSent" should {
    "remove all scheduled notifications for SANDBOX apps prior to a specific date" in new Setup {
      val applicationId = ApplicationId.random
      await(
        unusedApplicationRepository.collection
          .insertOne(sandboxApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1), LocalDate.now.plusDays(1))))
          .toFuture()
      )

      val result = await(unusedApplicationRepository.updateNotificationsSent(SANDBOX, applicationId))

      result should be(true)
      val updatedApplication: UnusedApplication =
        await(
          unusedApplicationRepository.collection
            .find(Filters.and(Filters.equal("environment", "SANDBOX"), Filters.equal("applicationId", Codecs.toBson(applicationId))))
            .toFuture()
            .map(_.toList.head)
        )
      updatedApplication.scheduledNotificationDates.size should be(1)
      updatedApplication.scheduledNotificationDates.head.isAfter(LocalDate.now) should be(true)
    }

    "remove all scheduled notifications for PRODUCTION apps prior to a specific date" in new Setup {
      val applicationId = ApplicationId.random
      await(
        unusedApplicationRepository.collection
          .insertOne(productionApplication(applicationId, scheduledNotificationDates = Seq(LocalDate.now.minusDays(2), LocalDate.now.minusDays(1), LocalDate.now.plusDays(1))))
          .toFuture()
      )

      val result = await(unusedApplicationRepository.updateNotificationsSent(PRODUCTION, applicationId))

      result should be(true)
      val updatedApplication: UnusedApplication =
        await(
          unusedApplicationRepository.collection
            .find(Filters.and(Filters.equal("environment", "PRODUCTION"), Filters.equal("applicationId", Codecs.toBson(applicationId))))
            .toFuture()
            .map(_.toList.head)
        )
      updatedApplication.scheduledNotificationDates.size should be(1)
      updatedApplication.scheduledNotificationDates.head.isAfter(LocalDate.now) should be(true)
    }
  }

  "applicationsToBeDeleted" should {
    "correctly retrieve SANDBOX applications that are scheduled to be deleted" in new Setup {
      val sandboxApplicationToBeDeleted: UnusedApplication    = sandboxApplication(ApplicationId.random, scheduledDeletionDate = LocalDate.now.minusDays(1))
      val sandboxApplicationToNotBeDeleted: UnusedApplication = sandboxApplication(ApplicationId.random, scheduledDeletionDate = LocalDate.now.plusDays(1))

      await(
        unusedApplicationRepository.collection
          .insertMany(
            Seq(
              sandboxApplicationToBeDeleted,
              sandboxApplicationToNotBeDeleted,
              productionApplication(ApplicationId.random),
              productionApplication(ApplicationId.random)
            )
          )
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplicationsToBeDeleted(SANDBOX, LocalDateTime.now))

      val returnedApplicationIds = results.map(_.applicationId)
      returnedApplicationIds.size should be(1)
      returnedApplicationIds.head should be(sandboxApplicationToBeDeleted.applicationId)
    }

    "correctly retrieve PRODUCTION applications that are scheduled to be deleted" in new Setup {
      val productionApplicationToBeDeleted: UnusedApplication    = productionApplication(ApplicationId.random, scheduledDeletionDate = LocalDate.now.minusDays(1))
      val productionApplicationToNotBeDeleted: UnusedApplication = productionApplication(ApplicationId.random, scheduledDeletionDate = LocalDate.now.plusDays(1))

      await(
        unusedApplicationRepository.collection.insertMany(
          Seq(
            sandboxApplication(ApplicationId.random),
            sandboxApplication(ApplicationId.random),
            productionApplicationToBeDeleted,
            productionApplicationToNotBeDeleted
          )
        )
          .toFuture()
      )

      val results = await(unusedApplicationRepository.unusedApplicationsToBeDeleted(PRODUCTION, LocalDateTime.now))

      val returnedApplicationIds = results.map(_.applicationId)
      returnedApplicationIds.size should be(1)
      returnedApplicationIds.head should be(productionApplicationToBeDeleted.applicationId)
    }
  }

  "deleteApplication" should {
    "correctly remove a SANDBOX application" in new Setup {
      val sandboxApplicationId = ApplicationId.random

      await(
        unusedApplicationRepository.collection
          .insertMany(Seq(sandboxApplication(sandboxApplicationId), productionApplication(ApplicationId.random), productionApplication(ApplicationId.random)))
          .toFuture()
      )

      val result = await(unusedApplicationRepository.deleteUnusedApplicationRecord(SANDBOX, sandboxApplicationId))

      result should be(true)
      await(unusedApplicationRepository.unusedApplications(SANDBOX)).size should be(0)
    }

    "correctly remove a PRODUCTION application" in new Setup {
      val productionApplicationId = ApplicationId.random

      await(
        unusedApplicationRepository.collection
          .insertMany(Seq(sandboxApplication(ApplicationId.random), productionApplication(productionApplicationId)))
          .toFuture()
      )

      val result = await(unusedApplicationRepository.deleteUnusedApplicationRecord(PRODUCTION, productionApplicationId))

      result should be(true)
      await(unusedApplicationRepository.unusedApplications(PRODUCTION)).size should be(0)
    }

    "return true if application id was not found in database" in {
      val result = await(unusedApplicationRepository.deleteUnusedApplicationRecord(PRODUCTION, ApplicationId.random))

      result should be(true)
    }

  }

  "ensure fix for loss of LDT in mongo java time Implicits works whilst we have 'bad' date (in string format)" should {
    "read date as string for last interactionDate" in {
      val rawMongoDoc =
        """{ "applicationId" : "c42f8e78-1539-457c-b4a2-cf6b8fc541b5", "applicationName" : "7 Days Unused App", "administrators" : [ { "emailAddress" : "imran.akram@digital.hmrc.gov.uk", "firstName" : "Imran", "lastName" : "Akram" } ], "environment" : "SANDBOX", "lastInteractionDate" : "2019-05-25T09:09:06.896", "scheduledNotificationDates" : [ ISODate("2023-05-21T00:00:00Z") ], "scheduledDeletionDate" : ISODate("2023-05-21T00:00:00Z") }
          |""".stripMargin
      saveRawMongoJson(rawMongoDoc).wasAcknowledged() shouldBe true
      val results     = await(unusedApplicationRepository.unusedApplicationsToBeNotified(Environments.SANDBOX))
      results.size shouldBe 1
    }

    "read date as LocalDate for last interactionDate" in {
      val rawMongoDoc =
        """{ "applicationId" : "c42f8e78-1539-457c-b4a2-cf6b8fc541b5", "applicationName" : "7 Days Unused App", "administrators" : [ { "emailAddress" : "imran.akram@digital.hmrc.gov.uk", "firstName" : "Imran", "lastName" : "Akram" } ], "environment" : "SANDBOX", "lastInteractionDate" : ISODate("2019-05-21T00:00:00Z"), "scheduledNotificationDates" : [ ISODate("2023-05-21T00:00:00Z") ], "scheduledDeletionDate" : ISODate("2023-05-21T00:00:00Z") }
          |""".stripMargin
      saveRawMongoJson(rawMongoDoc).wasAcknowledged() shouldBe true
      val results     = await(unusedApplicationRepository.unusedApplicationsToBeNotified(Environments.SANDBOX))
      results.size shouldBe 1
    }
  }

  def saveRawMongoJson(raw: String): InsertOneResult = {

    await(mongoDatabase.getCollection("unusedApplications").insertOne(Document(raw)).toFuture())
  }

}
