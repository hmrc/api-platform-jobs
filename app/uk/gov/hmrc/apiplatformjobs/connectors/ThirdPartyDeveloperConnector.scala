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

import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import play.api.libs.ws.WSBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper.*

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector._
import uk.gov.hmrc.apiplatformjobs.connectors.model.{GetOrCreateUserIdRequest, GetOrCreateUserIdResponse}

object ThirdPartyDeveloperConnector {

  case class CoreUserDetails(email: LaxEmailAddress, id: UserId)

  private[connectors] case class FindUserIdRequest(email: LaxEmailAddress)
  private[connectors] case class FindUserIdResponse(userId: UserId)
  private[connectors] case class DeleteDeveloperRequest(emailAddress: LaxEmailAddress)
  private[connectors] case class DeleteUnregisteredDevelopersRequest(emails: Seq[LaxEmailAddress])
  case class DeveloperResponse(email: LaxEmailAddress, firstName: String, lastName: String, verified: Boolean, userId: UserId)
  private[connectors] case class UnregisteredDeveloperResponse(email: LaxEmailAddress, userId: UserId)

  case class ThirdPartyDeveloperConnectorConfig(baseUrl: String)

  object JsonFormatters {
    import play.api.libs.json.Format
    import cats.data.NonEmptyList
    import play.api.libs.functional.syntax.toInvariantFunctorOps
    import play.api.libs.json.Writes
    implicit val FindUserIdResponseReads: Reads[FindUserIdResponse]                                     = Json.reads[FindUserIdResponse]
    implicit val FindUserIdRequestWrite: Writes[FindUserIdRequest]                                      = Json.writes[FindUserIdRequest]
    implicit val formatDeleteDeveloperRequest: Format[DeleteDeveloperRequest]                           = Json.format[DeleteDeveloperRequest]
    implicit val formatDeleteUnregisteredDevelopersRequest: Format[DeleteUnregisteredDevelopersRequest] = Json.format[DeleteUnregisteredDevelopersRequest]
    implicit val formatDeveloperResponse: Format[DeveloperResponse]                                     = Json.format[DeveloperResponse]
    implicit val formatUnregisteredDeveloperResponse: Format[UnregisteredDeveloperResponse]             = Json.format[UnregisteredDeveloperResponse]

   /* implicit def nonEmptyListFormat[A: Format]: Format[NonEmptyList[A]] =
      new Format[NonEmptyList[A]] {
        override def writes(o: NonEmptyList[A]): JsValue = Json.toJson(o.toList)

        override def reads(json: JsValue): JsResult[NonEmptyList[A]] =
          Json.fromJson[List[A]](json).flatMap { values =>
            NonEmptyList
              .fromList(values)
              .map(JsSuccess.apply(_))
              .getOrElse(JsError(s"non-empty list cannot be empty"))
          }
      }*/
      
      /*implicit def nelReads[A: Reads]: Reads[NonEmptyList[A]] =
      Reads.list[A].reads(_).flatMap {
        case Nil => JsError("List may not be empty")
        case h :: t => JsSuccess(NonEmptyList(h, t))
      }*/
/*
    implicit def writesNonEmptyList[T: Writes]: Writes[NonEmptyList[T]] = Writes[NonEmptyList[T]] { v =>
      JsArray((v.head :: v.tail).map(Json.toJson(_)))
    }

    implicit def nonEmptyListWriter[Error](implicit writer: Writes[Error]): Writes[NonEmptyList[Error]] =
      Writes.list[Error].contramap(_.toList)*/
/*
      implicit def nonEmptyListWrites[A: Writes]: Writes[NonEmptyList[A]] =
        Writes.of[List[A]].contramap[NonEmptyList[A]](_.toList)*/
    /*implicit val nelErrorWrites: Writes[NonEmptyList[Error]] = Writes { errors =>
      Json.toJson(errors.toList)
    }*/
  }
}

@Singleton
class ThirdPartyDeveloperConnector @Inject() (config: ThirdPartyDeveloperConnectorConfig, http: HttpClientV2)(implicit ec: ExecutionContext)
    extends ResponseUtils {

  import ThirdPartyDeveloperConnector._

  val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE

  def fetchUserId(email: LaxEmailAddress)(implicit hc: HeaderCarrier): Future[Option[CoreUserDetails]] = {
    http
      .post(url"${config.baseUrl}/developers/find-user-id")
      .withBody(Json.toJson(FindUserIdRequest(email)))
      .execute[Option[FindUserIdResponse]]
      .map(_.map(userIdResponse => CoreUserDetails(email, userIdResponse.userId)))
  }

  def getOrCreateUserId(getOrCreateUserIdRequest: GetOrCreateUserIdRequest)(implicit hc: HeaderCarrier): Future[GetOrCreateUserIdResponse] = {
    http
      .post(url"${config.baseUrl}/developers/user-id")
      .withBody(Json.toJson(getOrCreateUserIdRequest))
      .execute[GetOrCreateUserIdResponse]
  }

  def fetchUnverifiedDevelopers(createdBefore: Instant, limit: Int)(implicit hc: HeaderCarrier): Future[Seq[CoreUserDetails]] = {
    val queryParams: Seq[(String, String)] = Seq(
      "createdBefore" -> dateFormatter.format(createdBefore.asLocalDateTime),
      "limit"         -> limit.toString,
      "status"        -> "UNVERIFIED"
    )
    http
      .get(url"${config.baseUrl}/developers?$queryParams")
      .execute[Seq[DeveloperResponse]]
      .map(_.map(d => CoreUserDetails(d.email, d.userId)))
  }

  def fetchAllDevelopers(implicit hc: HeaderCarrier): Future[Seq[CoreUserDetails]] = {
    http
      .get(url"${config.baseUrl}/developers")
      .execute[Seq[DeveloperResponse]]
      .map(_.map(d => CoreUserDetails(d.email, d.userId)))
  }

  def fetchExpiredUnregisteredDevelopers(limit: Int)(implicit hc: HeaderCarrier): Future[Seq[CoreUserDetails]] = {
    http
      .get(url"${config.baseUrl}/unregistered-developer/expired?limit=${limit.toString()}")
      .execute[Seq[UnregisteredDeveloperResponse]]
      .map(_.map(u => CoreUserDetails(u.email, u.userId)))
  }

  def fetchVerifiedDevelopers(emailAddresses: Set[LaxEmailAddress]): Future[Seq[DeveloperResponse]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    for {
      developerDetails  <- http
                             .post(url"${config.baseUrl}/developers/get-by-emails")
                             .withBody(Json.toJson(emailAddresses.toSeq))
                             .execute[Seq[DeveloperResponse]]
      verifiedDevelopers = developerDetails.filter(_.verified)
    } yield verifiedDevelopers
  }

  def deleteDeveloper(email: LaxEmailAddress)(implicit hc: HeaderCarrier): Future[Int] = {
    http
      .post(url"${config.baseUrl}/developer/delete?notifyDeveloper=false")
      .withBody(Json.toJson(DeleteDeveloperRequest(email)))
      .execute[ErrorOr[HttpResponse]]
      .map(statusOrThrow)
  }

  def deleteUnregisteredDeveloper(email: LaxEmailAddress)(implicit hc: HeaderCarrier): Future[Int] = {
    http
      .post(url"${config.baseUrl}/unregistered-developer/delete")
      .withBody(Json.toJson(DeleteUnregisteredDevelopersRequest(Seq(email))))
      .execute[ErrorOr[HttpResponse]]
      .map(statusOrThrow)
  }

}
