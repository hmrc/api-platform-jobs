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

import play.api.Configuration
import uk.gov.hmrc.apiplatformjobs.connectors.EmailConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment.{Environment, PRODUCTION, SANDBOX}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.mongo.lock.LockRepository

import java.time.{LocalDateTime, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

abstract class SendUnusedApplicationNotificationsJob(unusedApplicationsRepository: UnusedApplicationsRepository,
                                                     emailConnector: EmailConnector,
                                                     environment: Environment,
                                                     configuration: Configuration,
                                                     lockRepository: LockRepository)
  extends UnusedApplicationsJob("SendUnusedApplicationNotificationsJob", environment, configuration, lockRepository) {

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val notificationTime = LocalDateTime.now(ZoneOffset.UTC)

    for {
      appsToNotify <- unusedApplicationsRepository.unusedApplicationsToBeNotified(environment, notificationTime)
      _ <- Future.sequence(appsToNotify.map { app =>
        emailConnector.sendApplicationToBeDeletedNotifications(app, environmentName(environment)) map {
          case true => unusedApplicationsRepository.updateNotificationsSent(environment, app.applicationId, notificationTime)
          case _ => logWarn(s"Unable to send notifications to any administrators of Application [${app.applicationName} (${app.applicationId})]")
        }
      })
    } yield RunningOfJobSuccessful

  }

}

@Singleton
class SendUnusedSandboxApplicationNotificationsJob @Inject()(unusedApplicationsRepository: UnusedApplicationsRepository,
                                                             emailConnector: EmailConnector,
                                                             configuration: Configuration,
                                                             lockRepository: LockRepository)
  extends SendUnusedApplicationNotificationsJob(unusedApplicationsRepository, emailConnector, SANDBOX, configuration, lockRepository)


@Singleton
class SendUnusedProductionApplicationNotificationsJob @Inject()(unusedApplicationsRepository: UnusedApplicationsRepository,
                                                                emailConnector: EmailConnector,
                                                                configuration: Configuration,
                                                                lockRepository: LockRepository)
  extends SendUnusedApplicationNotificationsJob(unusedApplicationsRepository, emailConnector, PRODUCTION, configuration, lockRepository)
