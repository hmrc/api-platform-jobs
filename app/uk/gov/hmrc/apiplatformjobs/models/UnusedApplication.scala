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

package uk.gov.hmrc.apiplatformjobs.models

import java.time.{LocalDate, LocalDateTime}

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

case class ApplicationUsageDetails(applicationId: ApplicationId, applicationName: String, administrators: Set[LaxEmailAddress], creationDate: LocalDateTime, lastAccessDate: Option[LocalDateTime])

// TODO - what is this - has this joined TPD and Collaborator ??
case class Administrator(emailAddress: LaxEmailAddress, firstName: String, lastName: String)
case object Administrator {
  def apply(emailAddress: LaxEmailAddress, firstName: String, lastName: String): Administrator = new Administrator(emailAddress, firstName, lastName)
}

case class UnusedApplication(
    applicationId: ApplicationId,
    applicationName: String,
    administrators: Seq[Administrator],
    environment: Environment,
    lastInteractionDate: LocalDateTime,
    scheduledNotificationDates: Seq[LocalDate],
    scheduledDeletionDate: LocalDate
)

object Environment extends Enumeration {
  type Environment = Value
  val SANDBOX, PRODUCTION = Value
}

object MongoFormat {
  implicit val localDateTimeFormat = MongoJavatimeFormats.localDateTimeFormat
  implicit val localDateFormat     = MongoJavatimeFormats.localDateFormat

  implicit def environmentWrites: Writes[Environment.Value] = (v: Environment.Value) => JsString(v.toString)
  implicit val environmentFormat: Format[Environment.Value] = Format(environmentReads(), environmentWrites)
  implicit val administratorFormat: Format[Administrator]   = Format(Json.reads[Administrator], Json.writes[Administrator])

  val unusedApplicationReads: Reads[UnusedApplication] = (
    (JsPath \ "applicationId").read[ApplicationId] and
      (JsPath \ "applicationName").read[String] and
      (JsPath \ "administrators").read[Seq[Administrator]] and
      (JsPath \ "environment").read[Environment] and
      (JsPath \ "lastInteractionDate").read[LocalDateTime] and
      (JsPath \ "scheduledNotificationDates").read[List[LocalDate]] and
      (JsPath \ "scheduledDeletionDate").read[LocalDate]
  )(UnusedApplication.apply _)

  def environmentReads(): Reads[Environment.Value] = {
    case JsString("SANDBOX")    => JsSuccess(Environment.SANDBOX)
    case JsString("PRODUCTION") => JsSuccess(Environment.PRODUCTION)
    case JsString(s)            =>
      try {
        JsSuccess(Environment.withName(s))
      } catch {
        case _: NoSuchElementException =>
          JsError(s"Enumeration expected of type: Environment, but it does not contain '$s'")
      }
    case _                      => JsError("String value expected")
  }

  implicit val unusedApplicationFormat: Format[UnusedApplication] = Format(unusedApplicationReads, Json.writes[UnusedApplication])
}
