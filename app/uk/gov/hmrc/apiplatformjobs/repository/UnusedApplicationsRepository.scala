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

import java.time.Clock
import org.mongodb.scala.model.InsertOneModel
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters.{and, equal, lte}
import org.mongodb.scala.model.{FindOneAndUpdateOptions, IndexModel, IndexOptions}
import org.mongodb.scala.model.Indexes.ascending
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatformjobs.models.{MongoFormat, UnusedApplication}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.time.LocalDateTime
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import org.mongodb.scala.model.Updates
import com.mongodb.client.model.ReturnDocument

@Singleton
class UnusedApplicationsRepository @Inject()(mongo: MongoComponent, val clock: Clock)(implicit val ec: ExecutionContext)
  extends PlayMongoRepository[UnusedApplication](
    collectionName = "unusedApplications",
    mongoComponent = mongo,
    domainFormat = MongoFormat.unusedApplicationFormat,
    indexes = Seq(
      IndexModel(ascending("environment", "applicationId"), IndexOptions()
        .name("applicationIdIndex")
        .unique(true)
        .background(true)
      ),
      IndexModel(ascending("environment", "scheduledNotificationDates"), IndexOptions()
        .name("scheduledNotificationDatesIndex")
        .unique(false)
        .background(true)
      ),
      IndexModel(ascending("environment", "scheduledDeletionDate"), IndexOptions()
        .name("scheduledDeletionDateIndex")
        .unique(false)
        .background(true)
      )
    ),
    replaceIndexes = true
  ) with MongoJavatimeFormats.Implicits {

  def unusedApplications(environment: Environment): Future[List[UnusedApplication]] = {
    collection.find(equal("environment", Codecs.toBson(environment)))
      .toFuture().map(_.toList)
  }

  def unusedApplicationsToBeNotified(environment: Environment, notificationDate: LocalDateTime = LocalDateTime.now(clock)): Future[List[UnusedApplication]] = {
    collection.find(and(
      equal("environment", Codecs.toBson(environment)),
      lte("scheduledNotificationDates", Codecs.toBson(notificationDate))
      )
    ).toFuture().map(_.toList)
  }

  def updateNotificationsSent(environment: Environment, applicationId: UUID, notificationDate: LocalDateTime = LocalDateTime.now(clock)): Future[Boolean] = {
    val query = Document("environment" -> Codecs.toBson(environment), "applicationId" -> Codecs.toBson(applicationId))

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.pullByFilter(lte("scheduledNotificationDates", Codecs.toBson(notificationDate))),
      options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
      .map(!_.scheduledNotificationDates.exists(y => y.atStartOfDay.isBefore(notificationDate))) // No notification dates prior to specified date
  }

  def unusedApplicationsToBeDeleted(environment: Environment, deletionDate: LocalDateTime = LocalDateTime.now(clock)): Future[List[UnusedApplication]] = {
    collection.find(and(equal("environment", Codecs.toBson(environment)), lte("scheduledDeletionDate", Codecs.toBson(deletionDate))))
      .toFuture().map(_.toList)
  }

  def deleteUnusedApplicationRecord(environment: Environment, applicationId: UUID): Future[Boolean] = {
    collection.deleteOne(Document("environment" -> Codecs.toBson(environment), "applicationId" -> Codecs.toBson(applicationId)))
      .toFuture().map(_.wasAcknowledged())

  }

  def bulkInsert(documents: Seq[UnusedApplication]): Future[Boolean]= {
    collection.bulkWrite(documents.map(InsertOneModel(_))).toFuture().map(_.wasAcknowledged())
  }
}
