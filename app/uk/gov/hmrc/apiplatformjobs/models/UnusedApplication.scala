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
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress

import java.time.format.DateTimeFormatter


case class ApplicationUsageDetails(
    applicationId: ApplicationId,
    applicationName: String,
    administrators: Set[LaxEmailAddress],
    creationDate: LocalDateTime,
    lastAccessDate: Option[LocalDateTime]
  )

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
    lastInteractionDate: LocalDate,
    scheduledNotificationDates: Seq[LocalDate],
    scheduledDeletionDate: LocalDate
  )

sealed trait Environment {
  def isSandbox: Boolean = this == Environments.SANDBOX

  def isProduction: Boolean = this == Environments.PRODUCTION
}

object Environment {

  def from(env: String) = env.toUpperCase match {
    case "PRODUCTION" => Some(Environments.PRODUCTION)
    case "SANDBOX"    => Some(Environments.SANDBOX)
    case _            => None
  }

  private val convert: String => JsResult[Environment] = (s) => Environment.from(s).fold[JsResult[Environment]](JsError(s"$s is not an environment"))(JsSuccess(_))

  implicit val reads: Reads[Environment] = (JsPath.read[String]).flatMapResult(convert(_))

  implicit val writes: Writes[Environment] = Writes[Environment](role => JsString(role.toString))

  implicit val format = Format(reads, writes)
}

object Environments {
  final case object PRODUCTION extends Environment
  final case object SANDBOX    extends Environment
}

object MongoFormat {

  implicit val localDateFormat     = MongoJavatimeFormats.localDateFormat

  implicit val administratorFormat: Format[Administrator] = Format(Json.reads[Administrator], Json.writes[Administrator])

  val unusedApplicationReads: Reads[UnusedApplication] = (
    (JsPath \ "applicationId").read[ApplicationId] and
      (JsPath \ "applicationName").read[String] and
      (JsPath \ "administrators").read[Seq[Administrator]] and
      (JsPath \ "environment").read[Environment] and
      ((JsPath \ "lastInteractionDate").read[LocalDate] or (JsPath \ "lastInteractionDate").read[String].map(raw => LocalDate.parse(raw, DateTimeFormatter.ISO_DATE_TIME)) ) and
      (JsPath \ "scheduledNotificationDates").read[List[LocalDate]] and
      (JsPath \ "scheduledDeletionDate").read[LocalDate]
  )(UnusedApplication.apply _)

  def environmentReads(): Reads[Environment] = {
    case JsString("SANDBOX")    => JsSuccess(Environments.SANDBOX)
    case JsString("PRODUCTION") => JsSuccess(Environments.PRODUCTION)
    case JsString(s)            =>
      Environment.from(s) match {
        case None    => JsError(s"Enumeration expected of type: Environment, but it does not contain '$s'")
        case Some(s) => JsSuccess(s)
      }
    case _                      => JsError("String value expected")
  }

  implicit val unusedApplicationFormat: Format[UnusedApplication] = Format(unusedApplicationReads, Json.writes[UnusedApplication])
}
