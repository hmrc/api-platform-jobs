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
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import org.apache.commons.codec.binary.Base64.encodeBase64String

import play.api.libs.json.Writes.LocalDateTimeEpochMilliWrites
import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HttpClient, _}

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
import uk.gov.hmrc.apiplatformjobs.models._

object ThirdPartyApplicationConnector {
  def toDomain(applications: List[ApplicationLastUseDate]): List[ApplicationUsageDetails] =
    applications.map(app => {
      val admins =
        app.collaborators
          .filter(_.role == "ADMINISTRATOR")
          .map(_.emailAddress)

      ApplicationUsageDetails(app.id, app.name, admins, app.createdOn, app.lastAccess)
    })

  case class DeleteCollaboratorRequest(
      email: String,
      adminsToEmail: Set[String],
      notifyCollaborator: Boolean
  )

  private[connectors] case class ApplicationResponse(id: String)
  private[connectors] case class Collaborator(emailAddress: String, role: String)
  private[connectors] case class ApplicationLastUseDate(id: UUID, name: String, collaborators: Set[Collaborator], createdOn: LocalDateTime, lastAccess: Option[LocalDateTime])
  private[connectors] case class PaginatedApplicationLastUseResponse(applications: List[ApplicationLastUseDate], page: Int, pageSize: Int, total: Int, matching: Int)

  case class ThirdPartyApplicationConnectorConfig(
      applicationSandboxBaseUrl: String,
      applicationSandboxUseProxy: Boolean,
      applicationSandboxBearerToken: String,
      applicationSandboxApiKey: String,
      sandboxAuthorisationKey: String,
      applicationProductionBaseUrl: String,
      applicationProductionUseProxy: Boolean,
      applicationProductionBearerToken: String,
      applicationProductionApiKey: String,
      productionAuthorisationKey: String
  )

  object JsonFormatters {

    implicit val dateTimeWriter: Writes[LocalDateTime] = LocalDateTimeEpochMilliWrites

    implicit val dateTimeReader: Reads[LocalDateTime] = {
      case JsNumber(n) => JsSuccess(Instant.ofEpochMilli(n.longValue()).atZone(ZoneOffset.UTC).toLocalDateTime)
      case _           => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.time"))))
    }

    implicit val writesDeleteCollaboratorRequest = Json.writes[DeleteCollaboratorRequest]

    implicit val dateTimeFormat: Format[LocalDateTime] = Format(dateTimeReader, dateTimeWriter)

    implicit val formatApplicationResponse: Format[ApplicationResponse]                             = Json.format[ApplicationResponse]
    implicit val formatCollaborator: Format[Collaborator]                                           = Json.format[Collaborator]
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

abstract class ThirdPartyApplicationConnector(implicit val ec: ExecutionContext) extends RepsonseUtils with ApplicationUpdateFormatters {

  protected val httpClient: HttpClient
  protected val proxiedHttpClient: ProxiedHttpClient
  val serviceBaseUrl: String
  val useProxy: Boolean
  val bearerToken: String
  val apiKey: String
  val authorisationKey: String

  def http: HttpClient = if (useProxy) proxiedHttpClient.withHeaders(bearerToken, apiKey) else httpClient

  def fetchAllApplications(implicit hc: HeaderCarrier): Future[Seq[Application]] =
    http.GET[Seq[Application]](s"$serviceBaseUrl/developer/applications")

  def fetchApplicationsByUserId(userId: UserId)(implicit hc: HeaderCarrier): Future[Seq[String]] = {
    http
      .GET[Seq[ApplicationResponse]](s"$serviceBaseUrl/developer/${userId.toString}/applications")
      .map(_.map(_.id))
  }

  def removeCollaborator(applicationId: String, email: String)(implicit hc: HeaderCarrier): Future[Int] = {
    val request = DeleteCollaboratorRequest(email = email, adminsToEmail = Set(), notifyCollaborator = false)
    http
      .POST[DeleteCollaboratorRequest, ErrorOr[HttpResponse]](s"$serviceBaseUrl/application/$applicationId/collaborator/delete", request)
      .map(statusOrThrow)
  }

  def applicationsLastUsedBefore(lastUseDate: LocalDateTime): Future[List[ApplicationUsageDetails]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    http
      .GET[PaginatedApplicationLastUseResponse](
        url = s"$serviceBaseUrl/applications",
        queryParams = Seq("lastUseBefore" -> DateTimeFormatter.ISO_DATE_TIME.format(lastUseDate), "sort" -> "NO_SORT")
      )
      .map(page => toDomain(page.applications))
  }

  def deleteApplication(applicationId: UUID, jobId: String, reasons: String, timestamp: LocalDateTime)(implicit hc: HeaderCarrier): Future[ApplicationUpdateResult] = {
    val deleteRequest = DeleteUnusedApplication(jobId, authorisationKey, reasons, timestamp)
    http
      .PATCH[DeleteUnusedApplication, Either[UpstreamErrorResponse, HttpResponse]](s"$serviceBaseUrl/application/$applicationId", deleteRequest)
      .map(_ match {
        case Right(result) => ApplicationUpdateSuccessResult
        case Left(_)       => ApplicationUpdateFailureResult
      })
  }
}

@Singleton
class SandboxThirdPartyApplicationConnector @Inject() (
    val config: ThirdPartyApplicationConnectorConfig,
    override val httpClient: HttpClient,
    override val proxiedHttpClient: ProxiedHttpClient
)(implicit override val ec: ExecutionContext)
    extends ThirdPartyApplicationConnector {

  val serviceBaseUrl           = config.applicationSandboxBaseUrl
  val useProxy                 = config.applicationSandboxUseProxy
  val bearerToken              = config.applicationSandboxBearerToken
  val apiKey                   = config.applicationSandboxApiKey
  val authorisationKey: String = encodeBase64String(config.sandboxAuthorisationKey.getBytes(UTF_8))

}

@Singleton
class ProductionThirdPartyApplicationConnector @Inject() (
    val config: ThirdPartyApplicationConnectorConfig,
    override val httpClient: HttpClient,
    override val proxiedHttpClient: ProxiedHttpClient
)(implicit override val ec: ExecutionContext)
    extends ThirdPartyApplicationConnector {

  val serviceBaseUrl           = config.applicationProductionBaseUrl
  val useProxy                 = config.applicationProductionUseProxy
  val bearerToken              = config.applicationProductionBearerToken
  val apiKey                   = config.applicationProductionApiKey
  val authorisationKey: String = encodeBase64String(config.productionAuthorisationKey.getBytes(UTF_8))

}
