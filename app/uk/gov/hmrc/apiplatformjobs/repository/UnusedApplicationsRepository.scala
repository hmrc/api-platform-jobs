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

package uk.gov.hmrc.apiplatformjobs.repository

import java.util.UUID

import akka.stream.Materializer
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.libs.json.{Format, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatformjobs.models.{MongoFormat, UnusedApplication}
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UnusedApplicationsRepository @Inject()(mongo: ReactiveMongoComponent)(implicit val mat: Materializer, val ec: ExecutionContext)
  extends ReactiveRepository[UnusedApplication, BSONObjectID]("unusedApplications", mongo.mongoConnector.db,
    MongoFormat.unusedApplicationFormat, ReactiveMongoFormats.objectIdFormats) {

  implicit val mongoDateFormats: Format[DateTime] = ReactiveMongoFormats.dateTimeFormats

  override def indexes = List(
    Index(
      key = List("environment" -> Ascending, "applicationId" -> Ascending),
      name = Some("applicationIdIndex"),
      unique = true,
      background = true),
    Index(
      key = List("environment" -> Ascending, "scheduledNotificationDates" -> Ascending),
      name = Some("scheduledNotificationDatesIndex"),
      unique = false,
      background = true),
    Index(
      key = List("environment" -> Ascending, "scheduledDeletionDate" -> Ascending),
      name = Some("scheduledDeletionDateIndex"),
      unique = false,
      background = true)
  )

  def unusedApplications()(implicit environment: Environment): Future[List[UnusedApplication]] = find("environment" -> environment)

  def unusedApplicationsToBeDeleted(deletionDate: DateTime = DateTime.now)(implicit environment: Environment): Future[List[UnusedApplication]] =
    find("environment" -> environment, "scheduledDeletionDate" -> Json.obj("$lte" -> deletionDate))

  def deleteUnusedApplicationRecord(applicationId: UUID)(implicit environment: Environment): Future[Boolean] =
    remove("environment" -> environment, "applicationId" -> applicationId)
      .map(_.ok)
}
