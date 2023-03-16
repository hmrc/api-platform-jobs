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

import scala.concurrent.{ExecutionContext, Future}
import cats.data.NonEmptyList
import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, InternalServerException}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, CommandFailure, DispatchRequest}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.ThirdPartyApplicationConnectorConfig
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatformjobs.models.HasSucceeded
import uk.gov.hmrc.http.BadRequestException

abstract class ApplicationCommandConnector(implicit val ec: ExecutionContext) extends ApplicationLogger {

  val serviceBaseUrl: String
  val http: HttpClient

  def dispatch(
      applicationId: ApplicationId,
      command: ApplicationCommand,
      adminsToEmail: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailureJsonFormatters._
    import uk.gov.hmrc.apiplatform.modules.common.services.NonEmptyListFormatters._
    import play.api.libs.json._
    import uk.gov.hmrc.http.HttpReads.Implicits._
    import play.api.http.Status._

    def baseApplicationUrl(applicationId: ApplicationId) = s"$serviceBaseUrl/application/${applicationId.value.toString()}"

    def parseWithLogAndThrow[T](input: String)(implicit reads: Reads[T]): T = {
      Json.parse(input).validate[T] match {
        case JsSuccess(t, _) =>
          logger.debug(s"Found dispatch command failure(s) - $t")
          t
        case JsError(err) =>
          logger.error(s"Failed to parse >>$input<< due to errors $err")
          throw new InternalServerException("Failed parsing response to dispatch")
      }
    }

    val url          = s"${baseApplicationUrl(applicationId)}/dispatch"
    val request      = DispatchRequest(command, adminsToEmail)
    val extraHeaders = Seq.empty[(String, String)]

    http
      .PATCH[DispatchRequest, HttpResponse](url, request, extraHeaders)
      .map(response =>
        response.status match {
          case OK          => HasSucceeded
          case BAD_REQUEST => parseWithLogAndThrow[NonEmptyList[CommandFailure]](response.body)
                              throw new BadRequestException("Dispatch failed - bad request")
          case status      => throw new InternalServerException(s"Failed calling dispatch http Status: $status")
        }
      )
  }
}

@Singleton
class SandboxApplicationCommandConnector @Inject() (
    val httpClient: HttpClient,
    val proxiedHttpClient: ProxiedHttpClient,
    val config: ThirdPartyApplicationConnectorConfig
  )(implicit override val ec: ExecutionContext
  ) extends ApplicationCommandConnector {

  val serviceBaseUrl = config.sandboxBaseUrl
  val useProxy       = config.sandboxUseProxy
  val bearerToken    = config.sandboxBearerToken
  val apiKey         = config.sandboxApiKey

  val http: HttpClient = if (useProxy) proxiedHttpClient.withHeaders(bearerToken, apiKey) else httpClient
}

@Singleton
class ProductionApplicationCommandConnector @Inject() (
    val httpClient: HttpClient,
    val config: ThirdPartyApplicationConnectorConfig
  )(implicit override val ec: ExecutionContext
  ) extends ApplicationCommandConnector {

  val serviceBaseUrl = config.productionBaseUrl
  val http           = httpClient
}
