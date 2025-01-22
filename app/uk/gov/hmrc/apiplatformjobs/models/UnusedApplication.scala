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

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate}

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment, LaxEmailAddress}

case class ApplicationUsageDetails(
    applicationId: ApplicationId,
    applicationName: ApplicationName,
    administrators: Set[LaxEmailAddress],
    creationDate: Instant,
    lastAccessDate: Option[Instant]
  )

// TODO - what is this - has this joined TPD and Collaborator ??
case class Administrator(emailAddress: LaxEmailAddress, firstName: String, lastName: String)

case object Administrator {
  def apply(emailAddress: LaxEmailAddress, firstName: String, lastName: String): Administrator = new Administrator(emailAddress, firstName, lastName)
}

case class UnusedApplication(
    applicationId: ApplicationId,
    applicationName: ApplicationName,
    administrators: Seq[Administrator],
    environment: Environment,
    lastInteractionDate: LocalDate,
    scheduledNotificationDates: Seq[LocalDate],
    scheduledDeletionDate: LocalDate
  )

object MongoFormat {

  implicit val localDateFormat: Format[LocalDate] = MongoJavatimeFormats.localDateFormat

  implicit val administratorFormat: Format[Administrator] = Format(Json.reads[Administrator], Json.writes[Administrator])

  val unusedApplicationReads: Reads[UnusedApplication] = (
    (JsPath \ "applicationId").read[ApplicationId] and
      (JsPath \ "applicationName").read[ApplicationName] and
      (JsPath \ "administrators").read[Seq[Administrator]] and
      (JsPath \ "environment").read[Environment] and
      ((JsPath \ "lastInteractionDate").read[LocalDate] or (JsPath \ "lastInteractionDate").read[String].map(raw => LocalDate.parse(raw, DateTimeFormatter.ISO_DATE_TIME))) and
      (JsPath \ "scheduledNotificationDates").read[List[LocalDate]] and
      (JsPath \ "scheduledDeletionDate").read[LocalDate]
  )(UnusedApplication.apply _)

  implicit val unusedApplicationFormat: Format[UnusedApplication] = Format(unusedApplicationReads, Json.writes[UnusedApplication])
}
