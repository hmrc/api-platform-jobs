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

package uk.gov.hmrc.apiplatformjobs.connectors

import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, _}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaborators, DeleteRestrictionType, PaginatedApplications}
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper.InstantConversionSyntax

import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails

@Singleton
class ThirdPartyOrchestratorConnector @Inject() (http: HttpClientV2, config: ThirdPartyOrchestratorConnector.Config)(implicit ec: ExecutionContext) extends ApplicationLogger {

  import config.serviceBaseUrl
  import ThirdPartyOrchestratorConnector._

  def fetchApplicationsByUserId(userId: UserId)(implicit hc: HeaderCarrier): Future[Seq[ApplicationWithCollaborators]] = {
    http
      .get(url"$serviceBaseUrl/developer/$userId/applications")
      .execute[Seq[ApplicationWithCollaborators]]
  }

  def applicationSearch(environment: Environment, lastUseDate: Option[Instant], deleteRestriction: DeleteRestrictionType): Future[List[ApplicationUsageDetails]] = {

    def asQueryParams(): Seq[(String, String)] = {
      val deleteRestrictionAndSort: Seq[(String, String)] = Seq(
        "deleteRestriction" -> deleteRestriction.toString,
        "sort"              -> "NO_SORT"
      )
      lastUseDate match {
        case Some(date: Instant) => deleteRestrictionAndSort ++ Seq("lastUseBefore" -> DateTimeFormatter.ISO_DATE_TIME.format(date.asLocalDateTime))
        case None                => deleteRestrictionAndSort
      }
    }

    implicit val hc: HeaderCarrier = HeaderCarrier()

    http.get(url"$serviceBaseUrl/environment/$environment/applications?${asQueryParams()}")
      .execute[PaginatedApplications]
      .map(page => toDomain(page.applications))
  }

  def getApplication(applicationId: ApplicationId)(implicit hc: HeaderCarrier): Future[Option[ApplicationWithCollaborators]] = {
    http
      .get(url"$serviceBaseUrl/applications/$applicationId")
      .execute[Option[ApplicationWithCollaborators]]
  }
}

object ThirdPartyOrchestratorConnector {

  def toDomain(applications: List[ApplicationWithCollaborators]): List[ApplicationUsageDetails] =
    applications.map(app => {
      val admins =
        app.collaborators
          .filter(_.isAdministrator)
          .map(_.emailAddress)

      ApplicationUsageDetails(app.id, app.details.name, admins, app.details.createdOn, app.details.lastAccess)
    })

  case class Config(serviceBaseUrl: String)
}
