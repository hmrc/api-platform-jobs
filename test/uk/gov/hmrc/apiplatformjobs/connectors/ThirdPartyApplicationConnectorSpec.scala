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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status
import play.api.http.Status.{OK, NO_CONTENT, BAD_REQUEST}
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.{ApplicationLastUseDate, ApplicationResponse, Collaborator, PaginatedApplicationLastUseResponse}
import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.Random

class ThirdPartyApplicationConnectorSpec extends UnitSpec with ScalaFutures with MockitoSugar {

  private val baseUrl = "https://example.com"

  class Setup(proxyEnabled: Boolean = false) {
    implicit val hc = HeaderCarrier()
    protected val mockHttpClient = mock[HttpClient]
    protected val mockProxiedHttpClient = mock[ProxiedHttpClient]
    val apiKeyTest = "5bb51bca-8f97-4f2b-aee4-81a4a70a42d3"
    val bearer = "TestBearerToken"

    val connector = new ThirdPartyApplicationConnector {
      val httpClient = mockHttpClient
      val proxiedHttpClient = mockProxiedHttpClient
      val serviceBaseUrl = baseUrl
      val useProxy = proxyEnabled
      val bearerToken = "TestBearerToken"
      val apiKey = apiKeyTest
    }
  }

  "http" when {
    "configured not to use the proxy" should {
      "use the HttpClient" in new Setup(proxyEnabled = false) {
        connector.http shouldBe mockHttpClient
      }
    }

    "configured to use the proxy" should {
      "use the ProxiedHttpClient with the correct authorisation" in new Setup(proxyEnabled = true) {
        when(mockProxiedHttpClient.withHeaders(bearer, apiKeyTest)).thenReturn(mockProxiedHttpClient)

        connector.http shouldBe mockProxiedHttpClient

        verify(mockProxiedHttpClient).withHeaders(bearer, apiKeyTest)
      }
    }
  }

  "fetchApplicationsByEmail" should {
    val email = "email@example.com"
    val url = baseUrl + "/developer/applications"
    val applicationResponses = List(ApplicationResponse("app id 1"), ApplicationResponse("app id 2"))

    "return application Ids" in new Setup {
      when(mockHttpClient.GET[Seq[ApplicationResponse]](meq(url), meq(Seq("emailAddress" -> email)))(any(), any(), any()))
        .thenReturn(Future.successful(applicationResponses))

      val result = await(connector.fetchApplicationsByEmail(email))

      result.size shouldBe 2
      result should contain allOf ("app id 1", "app id 2")
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttpClient.GET[Seq[ApplicationResponse]](meq(url), meq(Seq("emailAddress" -> email)))(any(), any(), any()))
        .thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.fetchApplicationsByEmail(email))
      }
    }
  }

  "removeCollaborator" should {
    val appId = "app ID"
    val email = "email@example.com"
    val url = baseUrl + s"/application/$appId/collaborator/email%40example.com?notifyCollaborator=false&adminsToEmail="

    "remove collaborator" in new Setup {
      when(mockHttpClient.DELETE[HttpResponse](meq(url), any())(any(), any(), any())).thenReturn(successful(HttpResponse(OK)))

      val result = await(connector.removeCollaborator(appId, email))

      result shouldBe OK
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttpClient.DELETE[HttpResponse](meq(url), any())(any(), any(), any())).thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.removeCollaborator(appId, email))
      }
    }
  }

  "applicationsLastUsedBefore" should {
    def paginatedResponse(lastUseDates: List[ApplicationLastUseDate]) =
      PaginatedApplicationLastUseResponse(lastUseDates, 1, 100, lastUseDates.size, lastUseDates.size)

    val dateFormatter: DateTimeFormatter = ISODateTimeFormat.dateTime()

    "return application details as ApplicationUsageDetails objects" in new Setup {
      val lastUseDate = DateTime.now.minusMonths(12)
      val encodedDateString: String = URLEncoder.encode(dateFormatter.withZoneUTC().print(lastUseDate), StandardCharsets.UTF_8.toString)
      val oldApplication1Admin: String = "foo@bar.com"
      val oldApplication1 =
        ApplicationLastUseDate(
          UUID.randomUUID(),
          Random.alphanumeric.take(10).mkString,
          Set(Collaborator(oldApplication1Admin, "ADMINISTRATOR"), Collaborator("a@b.com", "DEVELOPER")),
          DateTime.now.minusMonths(12),
          Some(DateTime.now.minusMonths(13)))
      val oldApplication2Admin: String = "bar@baz.com"
      val oldApplication2 =
        ApplicationLastUseDate(
          UUID.randomUUID(),
          Random.alphanumeric.take(10).mkString,
          Set(Collaborator(oldApplication2Admin, "ADMINISTRATOR"), Collaborator("b@c.com", "DEVELOPER")),
          DateTime.now.minusMonths(12),
          Some(DateTime.now.minusMonths(14)))

      when(mockHttpClient.GET[PaginatedApplicationLastUseResponse](
        meq( s"$baseUrl/application"),
        meq(Seq("lastUseBefore" -> encodedDateString)))(any(), any(), any()))
        .thenReturn(successful(paginatedResponse(List(oldApplication1, oldApplication2))))

      val results = await(connector.applicationsLastUsedBefore(lastUseDate))

      results should contain
        ApplicationUsageDetails(
          oldApplication1.id, oldApplication1.name, Set(oldApplication1Admin), oldApplication1.createdOn, oldApplication1.lastAccess)
      results should contain
        ApplicationUsageDetails(
          oldApplication2.id, oldApplication2.name, Set(oldApplication2Admin), oldApplication2.createdOn, oldApplication2.lastAccess)
    }

    "return empty Sequence when no results are returned" in new Setup {
      val lastUseDate = DateTime.now.minusMonths(12)
      val encodedDateString: String = URLEncoder.encode(dateFormatter.withZoneUTC().print(lastUseDate), StandardCharsets.UTF_8.toString)

      when(mockHttpClient.GET[PaginatedApplicationLastUseResponse](
        meq( s"$baseUrl/application"),
        meq(Seq("lastUseBefore" -> encodedDateString)))(any(), any(), any()))
        .thenReturn(successful(paginatedResponse(List.empty)))

      val results = await(connector.applicationsLastUsedBefore(lastUseDate))

      results.size should be (0)
    }
  }

  "deleteApplication" should {
    "return true if call to TPA is successful" in new Setup {
      val applicationId = UUID.randomUUID()
      val expectedUrl = s"$baseUrl/application/${applicationId.toString}/delete"

      when(mockHttpClient.POSTEmpty[HttpResponse](meq(expectedUrl), any())(any(), any(), any())).thenReturn(successful(HttpResponse(NO_CONTENT)))

      val response = await(connector.deleteApplication(applicationId))

      response should be (true)
    }

    "return false if call to TPA fails" in new Setup {
      val applicationId = UUID.randomUUID()
      val expectedUrl = s"$baseUrl/application/${applicationId.toString}/delete"

      when(mockHttpClient.POSTEmpty[HttpResponse](meq(expectedUrl), any())(any(), any(), any()))
        .thenReturn(successful(HttpResponse(BAD_REQUEST)))

      val response = await(connector.deleteApplication(applicationId))

      response should be (false)
    }
  }
}
