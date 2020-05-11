/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatformmicroservice.connectors

import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.Json
import play.mvc.Http.Status._
import uk.gov.hmrc.apiplatformmicroservice.models.UnusedApplicationToBeDeletedNotification
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class SendEmailRequest(to: Set[String],
                            templateId: String,
                            parameters: Map[String, String],
                            force: Boolean = false,
                            auditData: Map[String, String] = Map.empty,
                            eventUrl: Option[String] = None)

object SendEmailRequest {
  implicit val sendEmailRequestFmt = Json.format[SendEmailRequest]
}

@Singleton
class EmailConnector @Inject()(httpClient: HttpClient, config: EmailConfig)(implicit val ec: ExecutionContext) {
  val serviceUrl = config.baseUrl

  def sendApplicationToBeDeletedNotification(applicationToBeDeletedNotification: UnusedApplicationToBeDeletedNotification): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    post(
      SendEmailRequest(
        Set(applicationToBeDeletedNotification.userEmailAddress),
        "apiApplicationToBeDeletedNotification",
        Map(
          "userFirstName" -> applicationToBeDeletedNotification.userFirstName,
          "userLastName" -> applicationToBeDeletedNotification.userLastName,
          "applicationName" -> applicationToBeDeletedNotification.applicationName,
          "environmentName" -> applicationToBeDeletedNotification.environmentName,
          "timeSinceLastUse" -> applicationToBeDeletedNotification.timeSinceLastUse,
          "timeBeforeDeletion" -> applicationToBeDeletedNotification.timeBeforeDeletion,
          "dateOfScheduledDeletion" -> applicationToBeDeletedNotification.dateOfScheduledDeletion)))
  }

  private def post(payload: SendEmailRequest)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val url = s"$serviceUrl/hmrc/email"

    def extractError(response: HttpResponse): String = {
      Try(response.json \ "message") match {
        case Success(jsValue) => jsValue.as[String]
        case Failure(_) => s"Unable send email. Unexpected error for url=$url status=${response.status} response=${response.body}"
      }
    }

    httpClient.POST[SendEmailRequest, HttpResponse](url, payload)
      .map { response =>
        Logger.info(s"Sent '${payload.templateId}' to: ${payload.to.mkString(",")} with response: ${response.status}")
        response.status match {
          case status if status >= 200 && status <= 299 => true
          case NOT_FOUND =>
            Logger.error(s"Unable to send email. Downstream endpoint not found: $url")
            false
          case _ =>
            Logger.error(extractError(response))
            false
        }
      }
  }
}

case class EmailConfig(baseUrl: String)
