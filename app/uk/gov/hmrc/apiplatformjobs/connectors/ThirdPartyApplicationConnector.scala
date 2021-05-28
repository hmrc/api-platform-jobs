/*
 * Copyright 2021 HM Revenue & Customs
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
import java.util.UUID

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import javax.inject.{Inject, Singleton}
import org.apache.commons.codec.binary.Base64.encodeBase64String
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import play.api.http.Status._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.apiplatformjobs.models.ApplicationId
import uk.gov.hmrc.apiplatformjobs.connectors.model.FixCollaboratorRequest
import uk.gov.hmrc.apiplatformjobs.models.Application
import play.api.libs.json.Json
import play.api.Logger
import uk.gov.hmrc.apiplatformjobs.models.UserId

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
  private[connectors] case class ApplicationLastUseDate(id: UUID,
                                                        name: String,
                                                        collaborators: Set[Collaborator],
                                                        createdOn: DateTime,
                                                        lastAccess: Option[DateTime])
  private[connectors] case class PaginatedApplicationLastUseResponse(applications: List[ApplicationLastUseDate],
                                                                     page: Int,
                                                                     pageSize: Int,
                                                                     total: Int,
                                                                     matching: Int)

  case class ThirdPartyApplicationConnectorConfig(
    applicationSandboxBaseUrl: String, applicationSandboxUseProxy: Boolean, applicationSandboxBearerToken: String, applicationSandboxApiKey: String, sandboxAuthorisationKey: String,
    applicationProductionBaseUrl: String, applicationProductionUseProxy: Boolean, applicationProductionBearerToken: String, applicationProductionApiKey: String, productionAuthorisationKey: String
  )

  object JsonFormatters {
    import org.joda.time.DateTime
    import play.api.libs.json.JodaWrites._
    import play.api.libs.json._

    implicit val dateTimeWriter: Writes[DateTime] = JodaDateTimeNumberWrites

    implicit val dateTimeReader: Reads[DateTime] = {
      case JsNumber(n) => JsSuccess(new DateTime(n.toLong))
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.time"))))
    }

    implicit val writesDeleteCollaboratorRequest = Json.writes[DeleteCollaboratorRequest]

    implicit val dateTimeFormat: Format[DateTime] = Format(dateTimeReader, dateTimeWriter)

    implicit val formatApplicationResponse: Format[ApplicationResponse] = Json.format[ApplicationResponse]
    implicit val formatCollaborator: Format[Collaborator] = Json.format[Collaborator]
    implicit val formatApplicationLastUseDate: Format[ApplicationLastUseDate] = Json.format[ApplicationLastUseDate]
    implicit val formatPaginatedApplicationLastUseDate: Format[PaginatedApplicationLastUseResponse] = Json.format[PaginatedApplicationLastUseResponse]
  }
}

class ThirdPartyApplicationConnectorModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ThirdPartyApplicationConnector]).annotatedWith(Names.named("tpa-production")).to(classOf[ProductionThirdPartyApplicationConnector])
    bind(classOf[ThirdPartyApplicationConnector]).annotatedWith(Names.named("tpa-sandbox")).to(classOf[SandboxThirdPartyApplicationConnector])
  }
}

abstract class ThirdPartyApplicationConnector(implicit val ec: ExecutionContext) extends RepsonseUtils {
  val ISODateFormatter: DateTimeFormatter = ISODateTimeFormat.dateTime()

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

  def fixCollaborator(applicationId: ApplicationId, collaboratorRequest: FixCollaboratorRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    http.PUT[FixCollaboratorRequest, ErrorOr[Unit]](s"$serviceBaseUrl/application/${applicationId.value.toString}/collaborator", collaboratorRequest, Seq.empty)
    .map {
      case Right(_) =>         Future.successful(())
      case Left(UpstreamErrorResponse(_, CONFLICT, _, _)) => 
        Logger.warn(s"Conflict for $applicationId, $collaboratorRequest")
        Future.successful(())
      case Left(err) => throw err
    }
  }

  def fetchApplicationsByUserId(userId: UserId)(implicit hc: HeaderCarrier): Future[Seq[String]] = {
    http.GET[Seq[ApplicationResponse]](s"$serviceBaseUrl/developer/${userId.toString}/applications")
      .map(_.map(_.id))
  }

  def removeCollaborator(applicationId: String, email: String)(implicit hc: HeaderCarrier): Future[Int] = {
    val request = DeleteCollaboratorRequest(email = email, adminsToEmail = Set(), notifyCollaborator = false)
    http.POST[DeleteCollaboratorRequest, ErrorOr[HttpResponse]](s"$serviceBaseUrl/application/$applicationId/collaborator/delete", request)
      .map(statusOrThrow)
  }

  def applicationsLastUsedBefore(lastUseDate: DateTime): Future[List[ApplicationUsageDetails]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    http.GET[PaginatedApplicationLastUseResponse](
      url = s"$serviceBaseUrl/applications",
      queryParams = Seq("lastUseBefore" -> ISODateFormatter.withZoneUTC().print(lastUseDate), "sort" -> "NO_SORT"))
      .map(page => toDomain(page.applications))
  }

  def deleteApplication(applicationId: UUID): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authorisationKey)))

    http.POSTEmpty[HttpResponse](s"$serviceBaseUrl/application/${applicationId.toString}/delete")
    .map(_.status == NO_CONTENT)
  }
}

@Singleton
class SandboxThirdPartyApplicationConnector @Inject()(val config: ThirdPartyApplicationConnectorConfig,
                                                      override val httpClient: HttpClient,
                                                      override val proxiedHttpClient: ProxiedHttpClient)(implicit override val ec: ExecutionContext)
  extends ThirdPartyApplicationConnector {

  val serviceBaseUrl = config.applicationSandboxBaseUrl
  val useProxy = config.applicationSandboxUseProxy
  val bearerToken = config.applicationSandboxBearerToken
  val apiKey = config.applicationSandboxApiKey
  val authorisationKey: String = encodeBase64String(config.sandboxAuthorisationKey.getBytes(UTF_8))

}

@Singleton
class ProductionThirdPartyApplicationConnector @Inject()(val config: ThirdPartyApplicationConnectorConfig,
                                                         override val httpClient: HttpClient,
                                                         override val proxiedHttpClient: ProxiedHttpClient)(implicit override val ec: ExecutionContext)
  extends ThirdPartyApplicationConnector {

  val serviceBaseUrl = config.applicationProductionBaseUrl
  val useProxy = config.applicationProductionUseProxy
  val bearerToken = config.applicationProductionBearerToken
  val apiKey = config.applicationProductionApiKey
  val authorisationKey: String = encodeBase64String(config.productionAuthorisationKey.getBytes(UTF_8))

}
