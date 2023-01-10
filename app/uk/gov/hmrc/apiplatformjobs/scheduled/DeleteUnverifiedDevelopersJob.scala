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

import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.util.ApplicationLogger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import java.time.{LocalDateTime, ZoneOffset}
import javax.inject.Inject
import scala.concurrent.Future.sequence
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, duration}
import scala.util.control.NonFatal

class DeleteUnverifiedDevelopersJob @Inject() (
    override val lockService: DeleteUnverifiedDevelopersJobLockService,
    jobConfig: DeleteUnverifiedDevelopersJobConfig,
    val developerConnector: ThirdPartyDeveloperConnector,
    val sandboxApplicationConnector: SandboxThirdPartyApplicationConnector,
    val productionApplicationConnector: ProductionThirdPartyApplicationConnector
) extends ScheduledMongoJob
    with DeleteDeveloper
    with ApplicationLogger {

  override def name: String                            = "DeleteUnverifiedDevelopersJob"
  override def interval: FiniteDuration                = jobConfig.interval
  override def initialDelay: FiniteDuration            = jobConfig.initialDelay
  override val isEnabled: Boolean                      = jobConfig.enabled
  implicit val hc: HeaderCarrier                       = HeaderCarrier()
  override val deleteFunction: (String) => Future[Int] = developerConnector.deleteDeveloper
  val createdBeforeInDays                              = 30

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logger.info("Starting DeleteUnverifiedDevelopersJob")

    (for {
      developerDetails <- developerConnector.fetchUnverifiedDevelopers(LocalDateTime.now(ZoneOffset.UTC).minusDays(createdBeforeInDays), jobConfig.limit)
      _                 = logger.info(s"Found ${developerDetails.size} unverified developers")
      _                <- sequence(developerDetails.map(deleteDeveloper))
    } yield RunningOfJobSuccessful) recoverWith { case NonFatal(e) =>
      logger.error("Could not delete unverified developers", e)
      Future.failed(RunningOfJobFailed(name, e))
    }
  }
}

class DeleteUnverifiedDevelopersJobLockService @Inject() (repository: LockRepository) extends LockService {

  override val lockRepository: LockRepository = repository
  override val lockId: String                 = "DeleteUnverifiedDevelopersJob"
  override val ttl: duration.Duration         = 1.hours
}

case class DeleteUnverifiedDevelopersJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, limit: Int)
