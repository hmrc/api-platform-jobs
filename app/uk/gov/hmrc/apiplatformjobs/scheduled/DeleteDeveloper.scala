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

import java.time.LocalDateTime
import scala.concurrent.Future.sequence
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.connectors._
import uk.gov.hmrc.apiplatformjobs.models.HasSucceeded

trait DeleteDeveloper {
  self: ApplicationLogger =>

  def sandboxApplicationConnector: SandboxThirdPartyApplicationConnector
  def productionApplicationConnector: ProductionThirdPartyApplicationConnector
  def sandboxCmdConnector: SandboxApplicationCommandConnector
  def productionCmdConnector: ProductionApplicationCommandConnector
  def developerConnector: ThirdPartyDeveloperConnector

  val deleteFunction: (LaxEmailAddress) => Future[Int]

  def deleteDeveloper(jobLabel: String)(developer: CoreUserDetails)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[HasSucceeded] = {
    val timestamp = LocalDateTime.now()

    val matchesCoreDetails: (Collaborator) => Boolean = (collaborator) => collaborator.userId == developer.id && collaborator.emailAddress == developer.email

    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.ApplicationResponse

    def process(tpaConnector: ThirdPartyApplicationConnector, cmdConnector: ApplicationCommandConnector): Future[HasSucceeded] = {
      def processApp(appResponse: ApplicationResponse): Future[HasSucceeded] = {
        val collaborator = appResponse.collaborators.find(matchesCoreDetails).get // Safe to do here
        val command      = ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob(s"Delete-$jobLabel"), collaborator, timestamp)
        logger.info(s"Removing user:${collaborator.userId.value} from app:${appResponse.id.value}")
        cmdConnector.dispatch(appResponse.id, command, Set.empty)
      }

      for {
        apps <- tpaConnector.fetchApplicationsByUserId(developer.id)
        _    <- sequence(apps.map(processApp))
      } yield HasSucceeded
    }

    // Run the sandbox and production in parallel
    // Effectively the code will any and all collaborator records it can in both environments
    // If anything fails sequence(...) will capture that as the result and the final future will be a fail
    val sandboxResult: Future[HasSucceeded] = process(sandboxApplicationConnector, sandboxCmdConnector)
    val prodResult: Future[HasSucceeded]    = process(productionApplicationConnector, productionCmdConnector)

    for {
      _ <- sandboxResult
      _ <- prodResult
      _ <- deleteFunction(developer.email)
    } yield HasSucceeded
  }
}
