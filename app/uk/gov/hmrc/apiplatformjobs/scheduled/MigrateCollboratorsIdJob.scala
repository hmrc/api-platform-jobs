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

import javax.inject.Inject
import org.joda.time.Duration
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import com.google.inject.name.Named
import javax.inject.Singleton
import uk.gov.hmrc.http.HeaderCarrier
import scala.util.control.NonFatal
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector
import uk.gov.hmrc.apiplatformjobs.connectors.SandboxThirdPartyApplicationConnector
import uk.gov.hmrc.apiplatformjobs.models.Application
import uk.gov.hmrc.apiplatformjobs.connectors.model.GetOrCreateUserIdRequest
import uk.gov.hmrc.apiplatformjobs.connectors.model.FixCollaboratorRequest
import uk.gov.hmrc.apiplatformjobs.models.Collaborator
import uk.gov.hmrc.apiplatformjobs.models.ApplicationId
import uk.gov.hmrc.apiplatformjobs.connectors.ProductionThirdPartyApplicationConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector

@deprecated("Remove once the migration is complete","?")
@Singleton
class MigrateCollaboratorsIdJob @Inject()(val lockKeeper: MigrateCollaboratorsIdJobLockKeeper,
                                       subordinateApplicationConnector: SandboxThirdPartyApplicationConnector,
                                       principalApplicationConnector: ProductionThirdPartyApplicationConnector,
                                       developerConnector: ThirdPartyDeveloperConnector,
                                       @Named("migrateCollaboratorsId") jobConfig: JobConfig)(implicit ec: ExecutionContext) extends ScheduledMongoJob {

  override val name: String = "MigrateCollaboratorsIdJob"
  override val isEnabled: Boolean = jobConfig.enabled
  override val initialDelay: FiniteDuration = jobConfig.initialDelay
  override val interval: FiniteDuration = jobConfig.interval
  implicit val hc = HeaderCarrier()

  private def processCollaborators(applicationId: ApplicationId, deployedTo: Environment, collaborators: List[Collaborator]): Future[Unit] = {
    val applicationConnector = if(deployedTo == PRODUCTION) principalApplicationConnector else subordinateApplicationConnector

    collaborators match {
      case Nil => Future.successful(())
      case head :: tail => 
        val fixHead = for {
          details <- developerConnector.getOrCreateUserId(GetOrCreateUserIdRequest(head.emailAddress))
          fixed <- applicationConnector.fixCollaborator(applicationId, FixCollaboratorRequest(head.emailAddress, details.userId))
        } yield fixed

        fixHead.flatMap(_ => processCollaborators(applicationId, deployedTo, tail))
    }
  }

  private def processApplications(apps: List[Application], connectionEnv: Environment): Future[Unit] = {
    apps match {
      case Nil => Future.successful(())
      case head :: tail =>
        processCollaborators(head.id, connectionEnv, head.collaborators.filter(_.userId.isEmpty).toList).flatMap(_ => processApplications(tail, connectionEnv))
    }
  }

  private def processEnvironment(applicationConnector: ThirdPartyApplicationConnector, connectionEnv: Environment): Future[Unit] = {
    Logger.info(s"Processing $connectionEnv environment")
    val fapps = for {
      apps <- applicationConnector.fetchAllApplications(HeaderCarrier())
      missingIds = apps.filter(_.collaborators.find(_.userId.isEmpty).isDefined).toList
    } yield missingIds
    
    fapps.flatMap(appsToProcess => processApplications(appsToProcess, connectionEnv))
  }

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Logger.info("Starting MigrateCollaboratorsIdJob")

    val actions = 
      processEnvironment(subordinateApplicationConnector, SANDBOX)
      .flatMap(_ => processEnvironment(principalApplicationConnector, PRODUCTION))

    actions.onComplete {
      case _ => Logger.info("Finished MigrateCollaboratorsIdJob")
    }

    actions.map(_ => RunningOfJobSuccessful)
      .recoverWith {
        case NonFatal(e) =>
          // Logger.error(e.getMessage, e)
          Logger.error("Bang")
          Future.failed(RunningOfJobFailed(name, e))
      }
  }
}

class MigrateCollaboratorsIdJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
  override def lockId: String = "MigrateCollaboratorsIdJob"
  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60) // scalastyle:off magic.number
}
