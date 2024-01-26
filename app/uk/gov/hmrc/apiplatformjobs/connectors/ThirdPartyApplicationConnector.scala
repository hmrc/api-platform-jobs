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

import java.nio.charset.StandardCharsets.UTF_8
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import org.apache.commons.codec.binary.Base64.encodeBase64String

import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HttpClient, _}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress, UserId}

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
import uk.gov.hmrc.apiplatformjobs.models._

object ThirdPartyApplicationConnector {

  def toDomain(applications: List[ApplicationLastUseDate]): List[ApplicationUsageDetails] =
    applications.map(app => {
      val admins =
        app.collaborators
          .filter(_.isAdministrator)
          .map(_.emailAddress)

      ApplicationUsageDetails(app.id, app.name, admins, app.createdOn, app.lastAccess)
    })

  case class DeleteCollaboratorRequest(
      email: LaxEmailAddress,
      adminsToEmail: Set[LaxEmailAddress],
      notifyCollaborator: Boolean
    )

  case class ApplicationResponse(id: ApplicationId, collaborators: Set[Collaborator])

  private[connectors] case class ApplicationLastUseDate(
      id: ApplicationId,
      name: String,
      collaborators: Set[Collaborator],
      createdOn: Instant,
      lastAccess: Option[Instant]
    )
  private[connectors] case class PaginatedApplicationLastUseResponse(applications: List[ApplicationLastUseDate], page: Int, pageSize: Int, total: Int, matching: Int)

  case class ThirdPartyApplicationConnectorConfig(
      sandboxBaseUrl: String,
      sandboxUseProxy: Boolean,
      sandboxBearerToken: String,
      sandboxApiKey: String,
      sandboxAuthorisationKey: String,
      productionBaseUrl: String,
      productionAuthorisationKey: String
    )

  object JsonFormatters {

    // implicit val dateTimeWriter: Writes[LocalDateTime] = LocalDateTimeEpochMilliWrites

    // implicit val dateTimeReader: Reads[LocalDateTime] = {
    //   case JsNumber(n) => JsSuccess(Instant.ofEpochMilli(n.longValue).atZone(ZoneOffset.UTC).toLocalDateTime)
    //   case _           => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.time"))))
    // }

    implicit val writesDeleteCollaboratorRequest: Writes[DeleteCollaboratorRequest] = Json.writes[DeleteCollaboratorRequest]

    // implicit val dateTimeFormat: Format[LocalDateTime] = Format(dateTimeReader, dateTimeWriter)

    implicit val formatApplicationResponse: Format[ApplicationResponse]                             = Json.format[ApplicationResponse]
    implicit val formatApplicationLastUseDate: Format[ApplicationLastUseDate]                       = Json.format[ApplicationLastUseDate]
    implicit val formatPaginatedApplicationLastUseDate: Format[PaginatedApplicationLastUseResponse] = Json.format[PaginatedApplicationLastUseResponse]
  }
}

class ThirdPartyApplicationConnectorModule extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[ThirdPartyApplicationConnector]).annotatedWith(Names.named("tpa-production")).to(classOf[ProductionThirdPartyApplicationConnector])
    bind(classOf[ThirdPartyApplicationConnector]).annotatedWith(Names.named("tpa-sandbox")).to(classOf[SandboxThirdPartyApplicationConnector])
  }
}

abstract class ThirdPartyApplicationConnector(implicit val ec: ExecutionContext) extends ResponseUtils with ApplicationUpdateFormatters {

  protected val httpClient: HttpClient
  val serviceBaseUrl: String
  val authorisationKey: String
  val http: HttpClient

  def fetchAllApplications(implicit hc: HeaderCarrier): Future[Seq[Application]] =
    http.GET[Seq[Application]](s"$serviceBaseUrl/developer/applications")

  def fetchApplicationsByUserId(userId: UserId)(implicit hc: HeaderCarrier): Future[Seq[ApplicationResponse]] = {
    http
      .GET[Seq[ApplicationResponse]](s"$serviceBaseUrl/developer/$userId/applications")
  }

  def applicationSearch(lastUseDate: Option[Instant], allowAutoDelete: Boolean): Future[List[ApplicationUsageDetails]] = {

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC)

    def getQueryParams(lastUseDate: Option[Instant], allowAutoDelete: Boolean): Seq[(String, String)] = {
      val allowAutoDeleteAndSort: Seq[(String, String)] = Seq(
        "allowAutoDelete" -> allowAutoDelete.toString,
        "sort"            -> "NO_SORT"
      )
      lastUseDate match {
        case Some(date: Instant) => allowAutoDeleteAndSort ++ Seq("lastUseBefore" -> dateFormatter.format(date))
        case None                => allowAutoDeleteAndSort
      }
    }

    implicit val hc: HeaderCarrier = HeaderCarrier()

    http
      .GET[PaginatedApplicationLastUseResponse](
        url = s"$serviceBaseUrl/applications",
        queryParams = getQueryParams(lastUseDate, allowAutoDelete)
      )
      .map(page => toDomain(page.applications))
  }

  def deleteApplication(applicationId: ApplicationId, jobId: String, reasons: String, timestamp: Instant)(implicit hc: HeaderCarrier): Future[ApplicationUpdateResult] = {
    val deleteRequest = DeleteUnusedApplication(jobId, authorisationKey, reasons, timestamp)
    http
      .PATCH[DeleteUnusedApplication, Either[UpstreamErrorResponse, HttpResponse]](s"$serviceBaseUrl/application/${applicationId.value}", deleteRequest)
      .map(_ match {
        case Right(result) => ApplicationUpdateSuccessResult
        case Left(_)       => ApplicationUpdateFailureResult
      })
  }
}

@Singleton
class SandboxThirdPartyApplicationConnector @Inject() (
    config: ThirdPartyApplicationConnectorConfig,
    override val httpClient: HttpClient,
    proxiedHttpClient: ProxiedHttpClient
  )(implicit override val ec: ExecutionContext
  ) extends ThirdPartyApplicationConnector {

  val serviceBaseUrl           = config.sandboxBaseUrl
  val useProxy                 = config.sandboxUseProxy
  val bearerToken              = config.sandboxBearerToken
  val apiKey                   = config.sandboxApiKey
  val authorisationKey: String = encodeBase64String(config.sandboxAuthorisationKey.getBytes(UTF_8))

  override lazy val http: HttpClient = if (useProxy) proxiedHttpClient.withHeaders(bearerToken, apiKey) else httpClient

}

@Singleton
class ProductionThirdPartyApplicationConnector @Inject() (
    val config: ThirdPartyApplicationConnectorConfig,
    override val httpClient: HttpClient
  )(implicit override val ec: ExecutionContext
  ) extends ThirdPartyApplicationConnector {

  val serviceBaseUrl           = config.productionBaseUrl
  val authorisationKey: String = encodeBase64String(config.productionAuthorisationKey.getBytes(UTF_8))

  val http = httpClient
}
