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

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import play.api.Application
import play.api.http.Status.{OK, _}
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http._

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.JsonFormatters._
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector._
import uk.gov.hmrc.apiplatformjobs.models.UserId
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class ThirdPartyDeveloperConnectorSpec extends AsyncHmrcSpec with GuiceOneAppPerSuite with WiremockSugar {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val httpClient                 = app.injector.instanceOf[HttpClient]

    val config                 = ThirdPartyDeveloperConnectorConfig(wireMockUrl)
    val devEmail               = "joe.bloggs@example.com"
    val userId                 = UserId.random
    def endpoint(path: String) = s"$wireMockUrl/$path"

    val connector = new ThirdPartyDeveloperConnector(config, httpClient)
  }

  "fetchUnverifiedDevelopers" should {
    val limit = 10
    "return developer emails" in new Setup {

      stubFor(
        get(urlPathEqualTo("/developers"))
          .withQueryParam("createdBefore", equalTo("20200201"))
          .withQueryParam("limit", equalTo(s"${limit}"))
          .withQueryParam("status", equalTo("UNVERIFIED"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(Seq(DeveloperResponse(devEmail, "Fred", "Bloggs", verified = false, userId)))
          )
      )
      val result = await(connector.fetchUnverifiedDevelopers(LocalDateTime.of(2020, 2, 1, 0, 0), limit))

      result shouldBe Seq(CoreUserDetails(devEmail, userId))
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        get(urlEqualTo("/developers"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )
      intercept[UpstreamErrorResponse] {
        await(connector.fetchUnverifiedDevelopers(LocalDateTime.now(), limit))
      }
    }
  }

  "fetchAllDevelopers" should {
    "return all developer emails" in new Setup {
      stubFor(
        get(urlEqualTo("/developers"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(Seq(DeveloperResponse(devEmail, "Fred", "Bloggs", verified = true, userId)))
          )
      )
      val result = await(connector.fetchAllDevelopers)

      result.map(_.email) shouldBe Seq(devEmail)
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        get(urlEqualTo("/developers"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      intercept[UpstreamErrorResponse] {
        await(connector.fetchAllDevelopers)
      }
    }
  }

  "fetchExpiredUnregisteredDevelopers" should {
    val limit = 10

    "return developer emails" in new Setup {
      val devResponse1 = UnregisteredDeveloperResponse(devEmail, userId)

      stubFor(
        get(urlPathEqualTo("/unregistered-developer/expired"))
          .withQueryParam("limit", equalTo(s"${limit}"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(Seq(devResponse1))
          )
      )
      val result = await(connector.fetchExpiredUnregisteredDevelopers(limit))

      result shouldBe Seq(CoreUserDetails(devEmail, userId))
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        get(urlPathEqualTo("/unregistered-developer/expired"))
          .withQueryParam("limit", equalTo(s"${limit}"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      intercept[UpstreamErrorResponse] {
        await(connector.fetchExpiredUnregisteredDevelopers(limit))
      }
    }
  }

  "fetchVerifiedDevelopers" should {
    "return only verified developer details" in new Setup {
      val verifiedUserEmail     = "foo@baz.com"
      val verifiedUserFirstName = "Fred"
      val verifiedUserLastName  = "Bloggs"
      val unverifiedUserEmail   = "bar@baz.com"
      val unverifiedId          = UserId.random

      val dr1 = DeveloperResponse(verifiedUserEmail, verifiedUserFirstName, verifiedUserLastName, verified = true, userId)
      val dr2 = DeveloperResponse(unverifiedUserEmail, "", "", verified = false, userId = unverifiedId)

      stubFor(
        post(urlEqualTo("/developers/get-by-emails"))
          .withJsonRequestBody(Seq(verifiedUserEmail, unverifiedUserEmail))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(
                Seq(dr1, dr2)
              )
          )
      )

      val result = await(connector.fetchVerifiedDevelopers(Set(verifiedUserEmail, unverifiedUserEmail)))

      result shouldBe Seq(dr1)
    }

    "propagate error when endpoint returns error" in new Setup {
      val verifiedUserEmail   = "foo@baz.com"
      val unverifiedUserEmail = "bar@baz.com"

      stubFor(
        post(urlEqualTo("/developers/get-by-emails"))
          .withJsonRequestBody(Seq(verifiedUserEmail, unverifiedUserEmail))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
          )
      )
      intercept[UpstreamErrorResponse] {
        await(connector.fetchVerifiedDevelopers(Set(verifiedUserEmail, unverifiedUserEmail)))
      }
    }
  }

  "deleteDeveloper" should {
    "delete developer" in new Setup {
      stubFor(
        post(urlPathEqualTo("/developer/delete"))
          .withQueryParam("notifyDeveloper", equalTo("false"))
          .withJsonRequestBody(DeleteDeveloperRequest(devEmail))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )

      val result = await(connector.deleteDeveloper(devEmail))

      result shouldBe OK
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        post(urlPathEqualTo("/developer/delete"))
          .withQueryParam("notifyDeveloper", equalTo("false"))
          .withJsonRequestBody(DeleteDeveloperRequest(devEmail))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      intercept[UpstreamErrorResponse] {
        await(connector.deleteDeveloper(devEmail))
      }
    }
  }

  "deleteUnregisteredDeveloper" should {
    "delete unregistered developer" in new Setup {
      stubFor(
        post(urlEqualTo("/unregistered-developer/delete"))
          .withJsonRequestBody(DeleteUnregisteredDevelopersRequest(Seq(devEmail)))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )
      val result: Int = await(connector.deleteUnregisteredDeveloper(devEmail))

      result shouldBe OK
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        post(urlEqualTo("/unregistered-developer/delete"))
          .withJsonRequestBody(DeleteUnregisteredDevelopersRequest(Seq(devEmail)))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )
      intercept[UpstreamErrorResponse] {
        await(connector.deleteUnregisteredDeveloper(devEmail))
      }
    }
  }

}
