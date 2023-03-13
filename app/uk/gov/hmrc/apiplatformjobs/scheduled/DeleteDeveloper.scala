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

import scala.concurrent.Future.sequence
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.RemoveCollaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborators
import java.time.LocalDateTime
import uk.gov.hmrc.thirdpartydeveloperfrontend.connectors.ProductionApplicationCommandConnector
import uk.gov.hmrc.thirdpartydeveloperfrontend.connectors.SandboxApplicationCommandConnector
trait DeleteDeveloper {

  def sandboxApplicationConnector: SandboxThirdPartyApplicationConnector
  def productionApplicationConnector: ProductionThirdPartyApplicationConnector
  def sandboxCmdConnector: SandboxApplicationCommandConnector
  def productionCmdConnector: ProductionApplicationCommandConnector
  def developerConnector: ThirdPartyDeveloperConnector

  val deleteFunction: (LaxEmailAddress) => Future[Int]

  def deleteDeveloper(jobLabel: String)(developer: CoreUserDetails)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[CoreUserDetails] = {
    val timestamp = LocalDateTime.now()
    val collaboratorToDelete = Collaborators.Developer(developer.id, developer.email)

    for {
      sandboxAppIds    <- sandboxApplicationConnector.fetchApplicationsByUserId(developer.id)
      productionAppIds <- productionApplicationConnector.fetchApplicationsByUserId(developer.id)
      command           = RemoveCollaborator(Actors.ScheduledJob(s"Delete-$jobLabel"), collaboratorToDelete, timestamp)
      _                <- sequence(sandboxAppIds.map(sandboxCmdConnector.dispatch(_, command, Set.empty)))
      _                <- sequence(productionAppIds.map(productionCmdConnector.dispatch(_, command, Set.empty)))
      _                <- deleteFunction(developer.email)
    } yield developer
  }
}
