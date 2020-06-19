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

package uk.gov.hmrc.apiplatformjobs.connectors

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.{Format, JsValue, Json}
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.JsonFormatters.{formatDeleteDeveloperRequest, formatDeleteUnregisteredDevelopersRequest, formatDeveloperResponse}
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.{DeleteDeveloperRequest, DeleteUnregisteredDevelopersRequest, DeveloperResponse, ThirdPartyDeveloperConnectorConfig}
import uk.gov.hmrc.apiplatformjobs.models.EmailPreferences
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ThirdPartyDeveloperConnector @Inject()(config: ThirdPartyDeveloperConnectorConfig, http: HttpClient)(implicit ec: ExecutionContext) {

  val dateFormatter = ISODateTimeFormat.basicDate()

  def fetchUnverifiedDevelopers(createdBefore: DateTime, limit: Int)(implicit hc: HeaderCarrier): Future[Seq[String]] = {
    val queryParams = Seq("createdBefore" -> dateFormatter.print(createdBefore), "limit" -> limit.toString, "status" -> "UNVERIFIED")
    val result = http.GET[Seq[DeveloperResponse]](s"${config.baseUrl}/developers", queryParams)
    result.map(_.map(_.email))
  }

  def fetchAllDevelopers(implicit hc: HeaderCarrier): Future[Seq[String]] = {
    val result = http.GET[Seq[DeveloperResponse]](s"${config.baseUrl}/developers")
    result.map(_.map(_.email))
  }

  def fetchExpiredUnregisteredDevelopers(limit: Int)(implicit hc: HeaderCarrier): Future[Seq[String]] = {
    http.GET[Seq[DeveloperResponse]](s"${config.baseUrl}/unregistered-developer/expired", Seq("limit" -> limit.toString)).map(_.map(_.email))
  }

  def fetchVerifiedDevelopers(emailAddresses: Set[String]): Future[Seq[(String, String, String)]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    for {
      developerDetails <- http.POST[JsValue, Seq[DeveloperResponse]](s"${config.baseUrl}/developers/get-by-emails", Json.toJson(emailAddresses.toSeq))
      verifiedDevelopers = developerDetails.filter(_.verified)
    } yield verifiedDevelopers.map(dev => (dev.email, dev.firstName, dev.lastName))
  }

  def deleteDeveloper(email: String)(implicit hc: HeaderCarrier): Future[Int] = {
    http.POST(s"${config.baseUrl}/developer/delete?notifyDeveloper=false", DeleteDeveloperRequest(email)).map(_.status)
  }

  def deleteUnregisteredDeveloper(email: String)(implicit hc: HeaderCarrier): Future[Int] = {
    http.POST(s"${config.baseUrl}/unregistered-developer/delete", DeleteUnregisteredDevelopersRequest(Seq(email))).map(_.status)
  }

  def updateEmailPreferences(email: String, emailPreferences: EmailPreferences)(implicit hc: HeaderCarrier) = {
    http.PUT[EmailPreferences, HttpResponse](s"${config.baseUrl}/developer/$email/email-preferences", emailPreferences).map(_.status)
  }
}

object ThirdPartyDeveloperConnector {
  private[connectors] case class DeleteDeveloperRequest(emailAddress: String)
  private[connectors] case class DeleteUnregisteredDevelopersRequest(emails: Seq[String])
  private[connectors] case class DeveloperResponse(email: String, firstName: String, lastName: String, verified: Boolean)
  case class ThirdPartyDeveloperConnectorConfig(baseUrl: String)

  object JsonFormatters {
    implicit val formatDeleteDeveloperRequest: Format[DeleteDeveloperRequest] = Json.format[DeleteDeveloperRequest]
    implicit val formatDeleteUnregisteredDevelopersRequest: Format[DeleteUnregisteredDevelopersRequest] = Json.format[DeleteUnregisteredDevelopersRequest]
    implicit val formatDeveloperResponse: Format[DeveloperResponse] = Json.format[DeveloperResponse]
  }
}
