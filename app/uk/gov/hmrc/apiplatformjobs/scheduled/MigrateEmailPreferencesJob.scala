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

import cats.implicits._
import javax.inject.Inject
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.connectors.{ApiPlatformMicroserviceConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.{APIDefinition, EmailPreferences, EmailTopic, TaxRegimeInterests}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}

import scala.concurrent.Future.sequence
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class MigrateEmailPreferencesJob @Inject()(override val lockKeeper: MigrateEmailPreferencesJobLockKeeper,
                                                jobConfig: MigrateEmailPreferencesJobConfig,
                                                developerConnector: ThirdPartyDeveloperConnector,
                                                apiPlatformMicroserviceConnector: ApiPlatformMicroserviceConnector)

  extends ScheduledMongoJob {

  override def name: String = "MigrateEmailPreferencesJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override val isEnabled: Boolean = jobConfig.enabled
  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info("Starting MigrateEmailPreferencesJob")
    implicit val hc: HeaderCarrier = HeaderCarrier()

    (for {
      developerEmails <- developerConnector.fetchAllDevelopers
      _ = Logger.info(s"Found ${developerEmails.size} developers")
      _ <- sequence(developerEmails.map(migrateEmailPreferencesForDeveloper))
    } yield RunningOfJobSuccessful) recoverWith {
      case NonFatal(e) =>
        Logger.error("Could not migrate email preferences", e)
        Future.failed(RunningOfJobFailed(name, e))
    }
  }

  private def migrateEmailPreferencesForDeveloper(email: String)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Int] = {
    for {
      apiDefinitions <- apiPlatformMicroserviceConnector.fetchApiDefinitionsForCollaborator(email)
      interests = apiDefinitions.map(toInterestsMap).fold(Map.empty)(_ combine _)
      result <- developerConnector.updateEmailPreferences(email, EmailPreferences(toTaxRegimeInterests(interests), EmailTopic.values.toSet))
    } yield result
  }

  private def toInterestsMap(api: APIDefinition): Map[String, Set[String]] = {
    api.categories.map(categoryName => categoryName -> Set(api.serviceName)).toMap
  }

  private def toTaxRegimeInterests(interests: Map[String, Set[String]]): Seq[TaxRegimeInterests] = {
    interests.map { case (k, v) => TaxRegimeInterests(k, v)}.toSeq
  }
}

class MigrateEmailPreferencesJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "MigrateEmailPreferencesJob"

  override val forceLockReleaseAfter: Duration = Duration.standardHours(1)
}

case class MigrateEmailPreferencesJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
