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

package uk.gov.hmrc.apiplatformjobs.scheduled

import javax.inject.{Inject, Named, Singleton}
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment.{Environment, PRODUCTION, SANDBOX}
import uk.gov.hmrc.apiplatformjobs.models.UnusedApplication
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

abstract class DeleteUnusedApplicationsJob(thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                           unusedApplicationsRepository: UnusedApplicationsRepository,
                                           environment: Environment,
                                           configuration: Configuration,
                                           mongo: ReactiveMongoComponent)
  extends UnusedApplicationsJob("DeleteUnusedApplicationsJob", environment, configuration, mongo) {

  import scala.concurrent._

  final def sequentialFutures[I](fn: I => Future[Unit])(input: List[I])(implicit ec: ExecutionContext): Future[Unit] = input match {
    case Nil => Future.successful(())
    case head :: tail => 
      fn(head)
      .recover {
        case NonFatal(e) => ()
      }
      .flatMap(_ => sequentialFutures(fn)(tail))
  }

  // Not required currently. Retain for future reference
  //
  // final def batchFutures[I](batchSize: Int, batchPause: Long, fn: I => Future[Unit])(input: Seq[I])(implicit ec: ExecutionContext): Future[Unit] = {
  //   input.splitAt(batchSize) match {
  //     case (Nil, Nil) => Future.successful(())
  //     case (doNow: Seq[I], doLater: Seq[I]) => 
  //       Future.sequence(doNow.map(fn)).flatMap( _ => 
  //         Future(
  //           blocking({logDebug("Done batch of items"); Thread.sleep(batchPause)})
  //         )
  //         .flatMap(_ => batchFutures(batchSize, batchPause, fn)(doLater))
  //       )
  //   }
  // }

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    def deleteApplication(application: UnusedApplication): Future[Unit] = {
      logInfo(s"Deleting Application [${application.applicationName} (${application.applicationId})] in TPA")
      (for {
        deleteSuccessful <- thirdPartyApplicationConnector.deleteApplication(application.applicationId)
        _: Unit <- if(deleteSuccessful) {
          logInfo(s"Deletion successful - removing [${application.applicationName} (${application.applicationId})] from unusedApplications")
          unusedApplicationsRepository.deleteUnusedApplicationRecord(environment, application.applicationId)
          .map(_ => ())
        } else {
          logWarn(s"Unable to delete application [${application.applicationName} (${application.applicationId})]")
          Future.successful(())
        }
      } yield ())
      .recover {
          case NonFatal(e) =>
            logWarn(s"Unable to delete application [${application.applicationName} (${application.applicationId})]", e)
            ()
      }
    }

    for {
      applicationsToDelete <- unusedApplicationsRepository.unusedApplicationsToBeDeleted(environment)
      _ = logInfo(s"Found [${applicationsToDelete.size}] applications to delete")
      _ <- sequentialFutures(deleteApplication)(applicationsToDelete)
    } yield RunningOfJobSuccessful
  }
}

@Singleton
class DeleteUnusedSandboxApplicationsJob @Inject()(@Named("tpa-sandbox") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                   unusedApplicationsRepository: UnusedApplicationsRepository,
                                                   configuration: Configuration,
                                                   mongo: ReactiveMongoComponent)
  extends DeleteUnusedApplicationsJob(thirdPartyApplicationConnector, unusedApplicationsRepository, SANDBOX, configuration, mongo)

@Singleton
class DeleteUnusedProductionApplicationsJob @Inject()(@Named("tpa-production") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                      unusedApplicationsRepository: UnusedApplicationsRepository,
                                                      configuration: Configuration,
                                                      mongo: ReactiveMongoComponent)
  extends DeleteUnusedApplicationsJob(thirdPartyApplicationConnector, unusedApplicationsRepository, PRODUCTION, configuration, mongo)
