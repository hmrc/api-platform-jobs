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

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.CoreUserDetails
import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future.sequence
import scala.concurrent.{ExecutionContext, Future}
trait DeleteDeveloper {

  def sandboxApplicationConnector: SandboxThirdPartyApplicationConnector
  def productionApplicationConnector: ProductionThirdPartyApplicationConnector
  def developerConnector: ThirdPartyDeveloperConnector

  val deleteFunction: (String) => Future[Int]

  def deleteDeveloper(developer: CoreUserDetails)(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[CoreUserDetails] = {
    for {
      sandboxAppIds    <- sandboxApplicationConnector.fetchApplicationsByUserId(developer.id)
      productionAppIds <- productionApplicationConnector.fetchApplicationsByUserId(developer.id)
      _                <- sequence(sandboxAppIds.map(sandboxApplicationConnector.removeCollaborator(_, developer.email)))
      _                <- sequence(productionAppIds.map(productionApplicationConnector.removeCollaborator(_, developer.email)))
      _                <- deleteFunction(developer.email)
    } yield developer
  }
}
