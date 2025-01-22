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

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.util.control.NonFatal

import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.LockRepository

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteUnusedApplication
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment}
import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyOrchestratorConnector
import uk.gov.hmrc.apiplatformjobs.models.{ApplicationUpdateFailureResult, ApplicationUpdateResult, ApplicationUpdateSuccessResult, UnusedApplication}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.services.UnusedApplicationsService

abstract class DeleteUnusedApplicationsJob(
    tpoConnector: ThirdPartyOrchestratorConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    unusedApplicationsService: UnusedApplicationsService,
    environment: Environment,
    configuration: Configuration,
    override val clock: Clock,
    lockRepository: LockRepository
  ) extends UnusedApplicationsJob("DeleteUnusedApplicationsJob", environment, configuration, clock, lockRepository) with ClockNow {

  import scala.concurrent._

  final def sequentialFutures[I](fn: I => Future[Unit])(input: List[I])(implicit ec: ExecutionContext): Future[Unit] = input match {
    case Nil          => Future.successful(())
    case head :: tail =>
      fn(head)
        .recover { case NonFatal(e) =>
          ()
        }
        .flatMap(_ => sequentialFutures(fn)(tail))
  }

  private def deleteApplicationViaCmd(
      environment: Environment,
      applicationId: ApplicationId,
      jobId: String,
      reasons: String,
      timestamp: Instant
    )(implicit hc: HeaderCarrier,
      executionContext: ExecutionContext
    ): Future[ApplicationUpdateResult] = {

    val authorisationKey = if (environment.isProduction) unusedApplicationsConfiguration.production.authorisationKey else unusedApplicationsConfiguration.sandbox.authorisationKey

    val deleteRequest = DeleteUnusedApplication(jobId, authorisationKey, reasons, timestamp)

    tpoConnector.dispatchToEnvironment(environment, applicationId, deleteRequest, Set.empty)
      .map(_ => ApplicationUpdateSuccessResult)
      .recover {
        case NonFatal(_) => ApplicationUpdateFailureResult
      }
  }

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    def deleteApplication(application: UnusedApplication): Future[Unit] = {
      implicit val hc: HeaderCarrier = HeaderCarrier()
      logInfo(s"Deleting Application [${application.applicationName} (${application.applicationId})] in TPA")
      val reasons                    = s"Application automatically deleted because it has not been used since ${application.lastInteractionDate}"

      (for {
        deleteSuccessful <- deleteApplicationViaCmd(application.environment, application.applicationId, name, reasons, instant())
        _: Unit          <- if (deleteSuccessful == ApplicationUpdateSuccessResult) {
                              logInfo(s"Deletion successful - removing [${application.applicationName} (${application.applicationId})] from unusedApplications")
                              unusedApplicationsRepository
                                .deleteUnusedApplicationRecord(environment, application.applicationId)
                                .map(_ => ())
                            } else {
                              logWarn(s"Unable to delete application [${application.applicationName} (${application.applicationId})]")
                              Future.successful(())
                            }
      } yield ())
        .recover { case NonFatal(e) =>
          logWarn(s"Unable to delete application [${application.applicationName} (${application.applicationId})]", e)
          ()
        }
    }

    for {
      _                    <- unusedApplicationsService.updateUnusedApplications()
      applicationsToDelete <- unusedApplicationsRepository.unusedApplicationsToBeDeleted(environment)
      _                     = logInfo(s"Found [${applicationsToDelete.size}] applications to delete")
      _                    <- sequentialFutures(deleteApplication)(applicationsToDelete)
    } yield RunningOfJobSuccessful
  }
}

@Singleton
class DeleteUnusedSandboxApplicationsJob @Inject() (
    tpoConnector: ThirdPartyOrchestratorConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    unusedApplicationsService: UnusedApplicationsService,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
  ) extends DeleteUnusedApplicationsJob(
      tpoConnector,
      unusedApplicationsRepository,
      unusedApplicationsService,
      Environment.SANDBOX,
      configuration,
      clock,
      lockRepository
    )
    with ClockNow

@Singleton
class DeleteUnusedProductionApplicationsJob @Inject() (
    tpoConnector: ThirdPartyOrchestratorConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    unusedApplicationsService: UnusedApplicationsService,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
  ) extends DeleteUnusedApplicationsJob(
      tpoConnector,
      unusedApplicationsRepository,
      unusedApplicationsService,
      Environment.PRODUCTION,
      configuration,
      clock,
      lockRepository
    )
