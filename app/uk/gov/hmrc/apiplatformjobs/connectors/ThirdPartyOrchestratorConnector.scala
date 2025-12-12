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
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, _}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaborators, DeleteRestrictionType}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.Param._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.{ApplicationQueries, ApplicationQuery}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.services.QueryParamsToQueryStringMap
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails

@Singleton
class ThirdPartyOrchestratorConnector @Inject() (http: HttpClientV2, config: ThirdPartyOrchestratorConnector.Config)(implicit ec: ExecutionContext) extends ApplicationLogger {

  import config.serviceBaseUrl
  import ThirdPartyOrchestratorConnector._

  def fetchApplicationsByUserId(userId: UserId)(implicit hc: HeaderCarrier): Future[Seq[ApplicationWithCollaborators]] = {
    query[List[ApplicationWithCollaborators]](ApplicationQueries.applicationsByUserId(userId, false))
  }

  def applicationSearch(environment: Environment, lastUseDate: Option[Instant], deleteRestriction: DeleteRestrictionType): Future[List[ApplicationUsageDetails]] = {
    val deleteRestrictionQP: DeleteRestrictionQP = if (deleteRestriction == DeleteRestrictionType.DO_NOT_DELETE) DoNotDeleteQP else NoRestrictionQP
    val maybeDateQP                              = lastUseDate.map(date => LastUsedBeforeQP(date))

    implicit val hc: HeaderCarrier = HeaderCarrier()
    query[List[ApplicationWithCollaborators]](ApplicationQuery.GeneralOpenEndedApplicationQuery(
      EnvironmentQP(environment) :: ExcludeDeletedQP :: deleteRestrictionQP :: maybeDateQP.toList,
      limit = Some(100)
    ))
      .map(toDomain)
  }

  private def query[T](qry: ApplicationQuery)(implicit hc: HeaderCarrier, rds: HttpReads[T]): Future[T] = {
    val params                                 = QueryParamsToQueryStringMap.toQuery(qry)
    val singleValueParams: Map[String, String] = params.map {
      case (k, vs) => k -> vs.mkString
    }
    http.get(url"$serviceBaseUrl/query?$singleValueParams")
      .execute[T]
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
