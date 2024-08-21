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

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import play.api.libs.json.{Json, OFormat}
import play.mvc.Http.Status._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpResponse, StringContextOps}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import uk.gov.hmrc.apiplatformjobs.connectors.EmailConnector.toNotifications
import uk.gov.hmrc.apiplatformjobs.models.UnusedApplication

case class SendEmailRequest(
    to: Set[LaxEmailAddress],
    templateId: String,
    parameters: Map[String, String],
    force: Boolean = false,
    auditData: Map[String, String] = Map.empty,
    eventUrl: Option[String] = None
  )

object SendEmailRequest {
  implicit val sendEmailRequestFmt: OFormat[SendEmailRequest] = Json.format[SendEmailRequest]
}

@Singleton
class EmailConnector @Inject() (http: HttpClientV2, config: EmailConfig)(implicit val ec: ExecutionContext) extends ResponseUtils with ApplicationLogger {
  val serviceUrl = config.baseUrl

  def sendApplicationToBeDeletedNotifications(unusedApplication: UnusedApplication, environmentName: String): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val emailTemplateName          = "apiApplicationToBeDeletedNotification"

    val notifications = toNotifications(unusedApplication, environmentName)

    Future
      .sequence(notifications.map(notification => post(SendEmailRequest(Set(notification.userEmailAddress), emailTemplateName, notification.parameters()))))
      .map(_.contains(true))
  }

  private def post(payload: SendEmailRequest)(implicit hc: HeaderCarrier): Future[Boolean] = {

    val theUrl = url"$serviceUrl/hmrc/email"

    def extractError(response: HttpResponse): String = {
      Try(response.json \ "message") match {
        case Success(jsValue) => jsValue.as[String]
        case Failure(_)       => s"Unable send email. Unexpected error for url=$theUrl status=${response.status} response=${response.body}"
      }
    }

    http
      .post(theUrl)
      .withBody(Json.toJson(payload))
      .execute[HttpResponse]
      .map { response =>
        logger.info(s"Sent '${payload.templateId}' with response: ${response.status}")
        response.status match {
          case status if HttpErrorFunctions.is2xx(status) => true
          case NOT_FOUND                                  =>
            logger.error(s"Unable to send email. Downstream endpoint not found: $theUrl")
            false
          case _                                          =>
            logger.error(extractError(response))
            false
        }
      }
  }
}

object EmailConnector {

  def daysSince(date: LocalDate): Long = ChronoUnit.DAYS.between(date, LocalDate.now())

  def daysToDeletion(scheduledDeletionDate: LocalDate): String = {
    val daysToDeletion = ChronoUnit.DAYS.between(LocalDateTime.now().toLocalDate, scheduledDeletionDate.atStartOfDay())
    if (daysToDeletion == 1) s"${daysToDeletion.toString} day"
    else s"${daysToDeletion.toString} days"
  }

  val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")

  def toNotifications(unusedApplication: UnusedApplication, environmentName: String): Seq[UnusedApplicationToBeDeletedNotification] =
    unusedApplication.administrators.map { administrator =>
      UnusedApplicationToBeDeletedNotification(
        administrator.emailAddress,
        administrator.firstName,
        administrator.lastName,
        unusedApplication.applicationName,
        environmentName,
        s"${daysSince(unusedApplication.lastInteractionDate)} days",
        daysToDeletion(unusedApplication.scheduledDeletionDate)
      )
    }

  private[connectors] case class UnusedApplicationToBeDeletedNotification(
      userEmailAddress: LaxEmailAddress,
      userFirstName: String,
      userLastName: String,
      applicationName: String,
      environmentName: String,
      timeSinceLastUse: String,
      daysToDeletion: String
    ) {

    def parameters(): Map[String, String] =
      Map(
        "userFirstName"    -> userFirstName,
        "userLastName"     -> userLastName,
        "applicationName"  -> applicationName,
        "environmentName"  -> environmentName,
        "timeSinceLastUse" -> timeSinceLastUse,
        "daysToDeletion"   -> daysToDeletion
      )
  }
}

case class EmailConfig(baseUrl: String)
