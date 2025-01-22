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

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, _}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaborators, DeleteRestrictionType, PaginatedApplications}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, CommandFailure, DispatchRequest}
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.domain.services.NonEmptyListFormatters._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper.InstantConversionSyntax

import uk.gov.hmrc.apiplatformjobs.models.{ApplicationUsageDetails, HasSucceeded}

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

  def dispatchToEnvironment(
      environment: Environment,
      applicationId: ApplicationId,
      command: ApplicationCommand,
      adminsToEmail: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {

    import play.api.libs.json._
    import uk.gov.hmrc.http.HttpReads.Implicits._
    import play.api.http.Status._

    def parseWithLogAndThrow[T](input: String)(implicit reads: Reads[T]): T = {
      Json.parse(input).validate[T] match {
        case JsSuccess(t, _) =>
          logger.debug(s"Found dispatch command failure(s) - $t")
          t
        case JsError(err)    =>
          logger.error(s"Failed to parse >>$input<< due to errors $err")
          throw new InternalServerException("Failed parsing response to dispatch")
      }
    }

    http
      .patch(url"$serviceBaseUrl/environment/$environment/applications/$applicationId")
      .withBody(Json.toJson(DispatchRequest(command, adminsToEmail)))
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case OK          => HasSucceeded
          case BAD_REQUEST =>
            parseWithLogAndThrow[NonEmptyList[CommandFailure]](response.body)
            throw new BadRequestException("Dispatch failed - bad request")
          case status      => throw new InternalServerException(s"Failed calling dispatch http Status: $status")
        }
      )
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
