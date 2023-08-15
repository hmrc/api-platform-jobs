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

package uk.gov.hmrc.apiplatformjobs.services

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

import uk.gov.hmrc.apiplatformjobs.connectors.{ProductionThirdPartyApplicationConnector, SandboxThirdPartyApplicationConnector}
import uk.gov.hmrc.apiplatformjobs.models.{Environment, Environments}
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository

class UnusedApplicationsService @Inject() (
    productionThirdPartyApplicationConnector: ProductionThirdPartyApplicationConnector,
    sandboxThirdPartyApplicationConnector: SandboxThirdPartyApplicationConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository
  )(implicit val ec: ExecutionContext
  ) {

  def updateUnusedApplications() = {

    for {
      sandboxAppsNotToBeDeleted <- sandboxThirdPartyApplicationConnector.applicationSearch(None, false)
      sandboxResult             <- removeFromUnusedApplications(sandboxAppsNotToBeDeleted.map(_.applicationId).toSet, Environments.SANDBOX)
      prodAppsNotToBeDeleted    <- productionThirdPartyApplicationConnector.applicationSearch(None, false)
      prodResult                <- removeFromUnusedApplications(prodAppsNotToBeDeleted.map(_.applicationId).toSet, Environments.PRODUCTION)
    } yield sandboxResult.toList ++ prodResult.toList
  }

  private def removeFromUnusedApplications(applicationIds: Set[ApplicationId], environment: Environment): Future[Set[Boolean]] = {
    Future.sequence(applicationIds.map(unusedApplicationsRepository.deleteUnusedApplicationRecord(environment, _)))
  }

}
