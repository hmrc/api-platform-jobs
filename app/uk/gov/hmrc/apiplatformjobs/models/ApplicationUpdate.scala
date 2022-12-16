/*
 * Copyright 2022 HM Revenue & Customs
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

import java.time.LocalDateTime
import play.api.libs.json.Json
import play.api.libs.json._

trait ApplicationUpdate {
  def timestamp: LocalDateTime
}

case class DeleteUnusedApplication(jobId: String, authorisationKey: String, reasons: String, timestamp: LocalDateTime) extends ApplicationUpdate

trait ApplicationUpdateFormatters {
  implicit val deleteUnusedApplicationFormatter = Json.writes[DeleteUnusedApplication]
    .transform(_.as[JsObject] + ("updateType" -> JsString("deleteUnusedApplication")))
}

sealed trait ApplicationUpdateResult
case object ApplicationUpdateSuccessResult extends ApplicationUpdateResult
case object ApplicationUpdateFailureResult extends ApplicationUpdateResult
