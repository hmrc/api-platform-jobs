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

package uk.gov.hmrc.apiplatformjobs.connectors

import play.api.http.ContentTypes._
import play.api.http.HeaderNames._
import play.api.libs.json.Json
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector._
import uk.gov.hmrc.apiplatformjobs.connectors.model.{GetOrCreateUserIdRequest, GetOrCreateUserIdResponse}
import uk.gov.hmrc.apiplatformjobs.models.UserId
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HttpClient, _}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object ThirdPartyDeveloperConnector {

  case class CoreUserDetails(email: String, id: UserId)

  private[connectors] case class FindUserIdRequest(email: String)
  private[connectors] case class FindUserIdResponse(userId: UserId)
  private[connectors] case class DeleteDeveloperRequest(emailAddress: String)
  private[connectors] case class DeleteUnregisteredDevelopersRequest(emails: Seq[String])
  case class DeveloperResponse(email: String, firstName: String, lastName: String, verified: Boolean, userId: UserId)
  private[connectors] case class UnregisteredDeveloperResponse(email: String, userId: UserId)

  case class ThirdPartyDeveloperConnectorConfig(baseUrl: String)

  object JsonFormatters {
    import play.api.libs.json.Format
    implicit val FindUserIdResponseReads = Json.reads[FindUserIdResponse]
    implicit val FindUserIdRequestWrite = Json.writes[FindUserIdRequest]
    implicit val formatDeleteDeveloperRequest: Format[DeleteDeveloperRequest] = Json.format[DeleteDeveloperRequest]
    implicit val formatDeleteUnregisteredDevelopersRequest: Format[DeleteUnregisteredDevelopersRequest] = Json.format[DeleteUnregisteredDevelopersRequest]
    implicit val formatDeveloperResponse: Format[DeveloperResponse] = Json.format[DeveloperResponse]
    implicit val formatUnregisteredDeveloperResponse: Format[UnregisteredDeveloperResponse] = Json.format[UnregisteredDeveloperResponse]
  }
}

@Singleton
class ThirdPartyDeveloperConnector @Inject()(config: ThirdPartyDeveloperConnectorConfig, http: HttpClient)(implicit ec: ExecutionContext) extends RepsonseUtils {
  import ThirdPartyDeveloperConnector._

  val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE
  
  def fetchUserId(email: String)(implicit hc: HeaderCarrier): Future[Option[CoreUserDetails]] = {
    http.POST[FindUserIdRequest, Option[FindUserIdResponse]](s"${config.baseUrl}/developers/find-user-id", FindUserIdRequest(email))
    .map(_.map(userIdResponse => CoreUserDetails(email, userIdResponse.userId)))
  }

  def getOrCreateUserId(getOrCreateUserIdRequest: GetOrCreateUserIdRequest)(implicit hc: HeaderCarrier): Future[GetOrCreateUserIdResponse] = {
      http.POST[GetOrCreateUserIdRequest, GetOrCreateUserIdResponse](s"${config.baseUrl}/developers/user-id", getOrCreateUserIdRequest, Seq(CONTENT_TYPE -> JSON))
  }

  def fetchUnverifiedDevelopers(createdBefore: LocalDateTime, limit: Int)(implicit hc: HeaderCarrier): Future[Seq[CoreUserDetails]] = {
    val queryParams = Seq("createdBefore" -> dateFormatter.format(createdBefore), "limit" -> limit.toString, "status" -> "UNVERIFIED")
    http.GET[Seq[DeveloperResponse]](s"${config.baseUrl}/developers", queryParams)
    .map(_.map(d => CoreUserDetails(d.email, d.userId)))
  }

  def fetchAllDevelopers(implicit hc: HeaderCarrier): Future[Seq[CoreUserDetails]] = {
    http.GET[Seq[DeveloperResponse]](s"${config.baseUrl}/developers")
    .map(_.map(d => CoreUserDetails(d.email, d.userId)))
  }

  def fetchExpiredUnregisteredDevelopers(limit: Int)(implicit hc: HeaderCarrier): Future[Seq[CoreUserDetails]] = {
    http.GET[Seq[UnregisteredDeveloperResponse]](s"${config.baseUrl}/unregistered-developer/expired", Seq("limit" -> limit.toString))
    .map(_.map(u => CoreUserDetails(u.email, u.userId)))
  }

  def fetchVerifiedDevelopers(emailAddresses: Set[String]): Future[Seq[DeveloperResponse]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    for {
      developerDetails <- http.POST[Seq[String], Seq[DeveloperResponse]](s"${config.baseUrl}/developers/get-by-emails", emailAddresses.toSeq)
      verifiedDevelopers = developerDetails.filter(_.verified)
    } yield verifiedDevelopers
  }

  def deleteDeveloper(email: String)(implicit hc: HeaderCarrier): Future[Int] = {
    http.POST[DeleteDeveloperRequest,ErrorOr[HttpResponse]](s"${config.baseUrl}/developer/delete?notifyDeveloper=false", DeleteDeveloperRequest(email))
    .map(statusOrThrow)
  }

  def deleteUnregisteredDeveloper(email: String)(implicit hc: HeaderCarrier): Future[Int] = {
    http.POST[DeleteUnregisteredDevelopersRequest,ErrorOr[HttpResponse]](s"${config.baseUrl}/unregistered-developer/delete", DeleteUnregisteredDevelopersRequest(Seq(email)))
    .map(statusOrThrow)
  }
}

