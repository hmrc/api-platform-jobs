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

import java.time.{Clock, Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import com.mongodb.client.model.ReturnDocument
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters.{and, equal, lte}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{FindOneAndUpdateOptions, IndexModel, IndexOptions, InsertOneModel, Updates}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow

import uk.gov.hmrc.apiplatformjobs.models.{Environment, MongoFormat, UnusedApplication}

@Singleton
class UnusedApplicationsRepository @Inject() (mongo: MongoComponent, val clock: Clock)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[UnusedApplication](
      collectionName = "unusedApplications",
      mongoComponent = mongo,
      domainFormat = MongoFormat.unusedApplicationFormat,
      indexes = Seq(
        IndexModel(
          ascending("environment", "applicationId"),
          IndexOptions()
            .name("applicationIdIndex")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("environment", "scheduledNotificationDates"),
          IndexOptions()
            .name("scheduledNotificationDatesIndex")
            .unique(false)
            .background(true)
        ),
        IndexModel(
          ascending("environment", "scheduledDeletionDate"),
          IndexOptions()
            .name("scheduledDeletionDateIndex")
            .unique(false)
            .background(true)
        )
      ),
      replaceIndexes = true
    )
    with ClockNow
    with MongoJavatimeFormats.Implicits {

  override lazy val requiresTtlIndex: Boolean = false // Entries are managed by scheduled jobs

  def unusedApplications(environment: Environment): Future[List[UnusedApplication]] = {
    collection
      .find(equal("environment", Codecs.toBson(environment)))
      .toFuture()
      .map(_.toList)
  }

  def unusedApplicationsToBeNotified(environment: Environment, notificationDate: Instant = instant()): Future[List[UnusedApplication]] = {
    collection
      .find(
        and(
          equal("environment", Codecs.toBson(environment)),
          lte("scheduledNotificationDates", Codecs.toBson(notificationDate))
        )
      )
      .toFuture()
      .map(_.toList)
  }

  def updateNotificationsSent(environment: Environment, applicationId: ApplicationId, notificationDate: Instant = instant()): Future[Boolean] = {
    val query = Document("environment" -> Codecs.toBson(environment), "applicationId" -> Codecs.toBson(applicationId))

    collection
      .findOneAndUpdate(
        filter = query,
        update = Updates.pullByFilter(lte("scheduledNotificationDates", Codecs.toBson(notificationDate))),
        options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFuture()
      .map(!_.scheduledNotificationDates.exists(y => y.atStartOfDay.toInstant(ZoneOffset.UTC).isBefore(notificationDate))) // No notification dates prior to specified date
  }

  def unusedApplicationsToBeDeleted(environment: Environment, deletionDate: Instant = instant()): Future[List[UnusedApplication]] = {
    collection
      .find(and(equal("environment", Codecs.toBson(environment)), lte("scheduledDeletionDate", Codecs.toBson(deletionDate))))
      .toFuture()
      .map(_.toList)
  }

  def deleteUnusedApplicationRecord(environment: Environment, applicationId: ApplicationId): Future[Boolean] = {
    collection
      .deleteOne(Document("environment" -> Codecs.toBson(environment), "applicationId" -> Codecs.toBson(applicationId)))
      .toFuture()
      .map(_.wasAcknowledged())

  }

  def bulkInsert(documents: Seq[UnusedApplication]): Future[Boolean] = {
    collection.bulkWrite(documents.map(InsertOneModel(_))).toFuture().map(_.wasAcknowledged())
  }
}
