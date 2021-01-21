/*
 * Copyright 2021 HM Revenue & Customs
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

import org.joda.time.DateTime
import play.api.http.Status.OK
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.JsonFormatters.{formatDeleteDeveloperRequest, formatDeleteUnregisteredDevelopersRequest}
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector._
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.time.DateTimeUtils.now

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful

import uk.gov.hmrc.http.HttpReads.Implicits

import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class ThirdPartyDeveloperConnectorSpec extends AsyncHmrcSpec {

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockHttp: HttpClient = mock[HttpClient]
    val baseUrl = "http://third-party-developer"
    val config = ThirdPartyDeveloperConnectorConfig(baseUrl)
    val devEmail = "joe.bloggs@example.com"
    def endpoint(path: String) = s"$baseUrl/$path"

    val connector = new ThirdPartyDeveloperConnector(config, mockHttp)
  }

  "fetchUnverifiedDevelopers" should {
    val limit = 10
    "return developer emails" in new Setup {
      when(mockHttp.GET[Seq[DeveloperResponse]](eqTo(endpoint("developers")),
        eqTo(Seq("createdBefore" -> "20200201", "limit" -> s"$limit", "status" -> "UNVERIFIED")))(*, *, *))
        .thenReturn(successful(Seq(DeveloperResponse(devEmail, "Fred", "Bloggs", verified = false))))

      val result: Seq[String] = await(connector.fetchUnverifiedDevelopers(new DateTime(2020, 2, 1, 0, 0), limit))

      result shouldBe Seq(devEmail)
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttp.GET[Seq[DeveloperResponse]](eqTo(endpoint("developers")), *)(*, *, *)).thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.fetchUnverifiedDevelopers(now, limit))
      }
    }
  }

  "fetchAllDevelopers" should {
    "return all developer emails" in new Setup {
      when(mockHttp.GET[Seq[DeveloperResponse]](eqTo(endpoint("developers")))(*, *, *))
        .thenReturn(successful(Seq(DeveloperResponse(devEmail, "Fred", "Bloggs", verified = true))))

      val result: Seq[String] = await(connector.fetchAllDevelopers)

      result shouldBe Seq(devEmail)
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttp.GET[Seq[DeveloperResponse]](eqTo(endpoint("developers"))) (*, *, *)).thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.fetchAllDevelopers)
      }
    }
  }


  "fetchExpiredUnregisteredDevelopers" should {
    val limit = 10
    "return developer emails" in new Setup {
      when(mockHttp.GET[Seq[UnregisteredDeveloperResponse]](eqTo(endpoint("unregistered-developer/expired")), eqTo(Seq("limit" -> s"$limit")))(*, *, *))
        .thenReturn(successful(Seq(UnregisteredDeveloperResponse(devEmail))))

      val result: Seq[String] = await(connector.fetchExpiredUnregisteredDevelopers(limit))

      result shouldBe Seq(devEmail)
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttp.GET[Seq[UnregisteredDeveloperResponse]](eqTo(endpoint("unregistered-developer/expired")), *)(*, *, *)).thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.fetchExpiredUnregisteredDevelopers(limit))
      }
    }
  }

  "fetchVerifiedDevelopers" should {
    "return only verified developer details" in new Setup {
      val verifiedUserEmail = "foo@baz.com"
      val verifiedUserFirstName = "Fred"
      val verifiedUserLastName = "Bloggs"
      val unverifiedUserEmail = "bar@baz.com"

      when(mockHttp.POST[JsValue, Seq[DeveloperResponse]](
        eqTo(endpoint("developers/get-by-emails")),
        eqTo(Json.toJson(Seq(verifiedUserEmail, unverifiedUserEmail))),
        *)(*, *, *, *))
        .thenReturn(
          successful(
            Seq(
              DeveloperResponse(verifiedUserEmail, verifiedUserFirstName, verifiedUserLastName, verified = true),
              DeveloperResponse(unverifiedUserEmail, "", "", verified = false))))

      val result = await(connector.fetchVerifiedDevelopers(Set(verifiedUserEmail, unverifiedUserEmail)))

      result shouldBe Seq((verifiedUserEmail, verifiedUserFirstName, verifiedUserLastName))
    }

    "propagate error when endpoint returns error" in new Setup {
      val verifiedUserEmail = "foo@baz.com"
      val unverifiedUserEmail = "bar@baz.com"
      when(mockHttp.POST[JsValue, Seq[DeveloperResponse]](
        eqTo(endpoint("developers/get-by-emails")),
        eqTo(Json.toJson(Seq(verifiedUserEmail, unverifiedUserEmail))),
        *)(*, *, *, *))
        .thenReturn(Future.failed(new BadRequestException("")))

      intercept[BadRequestException] {
        await(connector.fetchVerifiedDevelopers(Set(verifiedUserEmail, unverifiedUserEmail)))
      }
    }
  }

  "deleteDeveloper" should {
    "delete developer" in new Setup {
      when(mockHttp.POST(endpoint("developer/delete?notifyDeveloper=false"), DeleteDeveloperRequest(devEmail))).thenReturn(successful(HttpResponse(OK)))

      val result: Int = await(connector.deleteDeveloper(devEmail))

      result shouldBe OK
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttp.POST(endpoint("developer/delete?notifyDeveloper=false"), DeleteDeveloperRequest(devEmail))).thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.deleteDeveloper(devEmail))
      }
    }
  }

  "deleteUnregisteredDeveloper" should {
    "delete unregistered developer" in new Setup {
      when(mockHttp.POST(endpoint("unregistered-developer/delete"), DeleteUnregisteredDevelopersRequest(Seq(devEmail)))).thenReturn(successful(HttpResponse(OK)))

      val result: Int = await(connector.deleteUnregisteredDeveloper(devEmail))

      result shouldBe OK
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttp.POST(endpoint("unregistered-developer/delete"), DeleteUnregisteredDevelopersRequest(Seq(devEmail)))).thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.deleteUnregisteredDeveloper(devEmail))
      }
    }
  }

}
