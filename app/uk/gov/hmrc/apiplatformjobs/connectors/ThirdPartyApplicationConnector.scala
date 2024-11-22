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
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import org.apache.commons.codec.binary.Base64.encodeBase64String

import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaborators
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.domain.services.InstantJsonFormatter.WithTimeZone.instantWithTimeZoneWrites
import uk.gov.hmrc.apiplatform.modules.common.domain.services.InstantJsonFormatter.lenientInstantReads
import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper.InstantConversionSyntax

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.utils.EbridgeConfigurator

object ThirdPartyApplicationConnector {

  def toDomain(applications: List[ApplicationWithCollaborators]): List[ApplicationUsageDetails] =
    applications.map(app => {
      val admins =
        app.collaborators
          .filter(_.isAdministrator)
          .map(_.emailAddress)

      ApplicationUsageDetails(app.id, app.details.name, admins, app.details.createdOn, app.details.lastAccess)
    })

  case class DeleteCollaboratorRequest(
      email: LaxEmailAddress,
      adminsToEmail: Set[LaxEmailAddress],
      notifyCollaborator: Boolean
    )

  private[connectors] case class PaginatedApplicationLastUseResponse(
      applications: List[ApplicationWithCollaborators],
      page: Int,
      pageSize: Int,
      total: Int,
      matching: Int
    )

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

    implicit val dateFormat: Format[Instant] = Format(lenientInstantReads, instantWithTimeZoneWrites)

    implicit val writesDeleteCollaboratorRequest: Writes[DeleteCollaboratorRequest] = Json.writes[DeleteCollaboratorRequest]

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

  val serviceBaseUrl: String
  val authorisationKey: String
  val http: HttpClientV2

  def configureEbridgeIfRequired: RequestBuilder => RequestBuilder

  def fetchAllApplications(implicit hc: HeaderCarrier): Future[Seq[Application]] =
    configureEbridgeIfRequired(
      http.get(url"$serviceBaseUrl/developer/applications")
    )
      .execute[Seq[Application]]

  def fetchApplicationsByUserId(userId: UserId)(implicit hc: HeaderCarrier): Future[Seq[ApplicationWithCollaborators]] = {
    configureEbridgeIfRequired(
      http.get(url"$serviceBaseUrl/developer/$userId/applications")
    )
      .execute[Seq[ApplicationWithCollaborators]]
  }

  def applicationSearch(lastUseDate: Option[Instant], allowAutoDelete: Boolean): Future[List[ApplicationUsageDetails]] = {

    def asQueryParams(): Seq[(String, String)] = {
      val allowAutoDeleteAndSort: Seq[(String, String)] = Seq(
        "allowAutoDelete" -> allowAutoDelete.toString,
        "sort"            -> "NO_SORT"
      )
      lastUseDate match {
        case Some(date: Instant) => allowAutoDeleteAndSort ++ Seq("lastUseBefore" -> DateTimeFormatter.ISO_DATE_TIME.format(date.asLocalDateTime))
        case None                => allowAutoDeleteAndSort
      }
    }

    implicit val hc: HeaderCarrier = HeaderCarrier()

    configureEbridgeIfRequired(
      http.get(url"$serviceBaseUrl/applications?${asQueryParams()}")
    )
      .execute[PaginatedApplicationLastUseResponse]
      .map(page => toDomain(page.applications))
  }

  def deleteApplication(applicationId: ApplicationId, jobId: String, reasons: String, timestamp: Instant)(implicit hc: HeaderCarrier): Future[ApplicationUpdateResult] = {
    val deleteRequest = DeleteUnusedApplication(jobId, authorisationKey, reasons, timestamp)
    configureEbridgeIfRequired(
      http
        .patch(url"$serviceBaseUrl/application/${applicationId.value}")
        .withBody(Json.toJson(deleteRequest))
    )
      .execute[Either[UpstreamErrorResponse, HttpResponse]]
      .map(_ match {
        case Right(result) => ApplicationUpdateSuccessResult
        case Left(_)       => ApplicationUpdateFailureResult
      })
  }
}

@Singleton
class SandboxThirdPartyApplicationConnector @Inject() (
    val config: ThirdPartyApplicationConnectorConfig,
    val http: HttpClientV2
  )(implicit override val ec: ExecutionContext
  ) extends ThirdPartyApplicationConnector {

  val serviceBaseUrl           = config.sandboxBaseUrl
  val useProxy                 = config.sandboxUseProxy
  val bearerToken              = config.sandboxBearerToken
  val apiKey                   = config.sandboxApiKey
  val authorisationKey: String = encodeBase64String(config.sandboxAuthorisationKey.getBytes(UTF_8))

  val configureEbridgeIfRequired: RequestBuilder => RequestBuilder = EbridgeConfigurator.configure(useProxy, bearerToken, apiKey)
}

@Singleton
class ProductionThirdPartyApplicationConnector @Inject() (
    val config: ThirdPartyApplicationConnectorConfig,
    val http: HttpClientV2
  )(implicit override val ec: ExecutionContext
  ) extends ThirdPartyApplicationConnector {

  val configureEbridgeIfRequired: RequestBuilder => RequestBuilder = identity

  val serviceBaseUrl           = config.productionBaseUrl
  val authorisationKey: String = encodeBase64String(config.productionAuthorisationKey.getBytes(UTF_8))
}
