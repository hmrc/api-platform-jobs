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

import play.api.http.Status._
import play.api.http.HeaderNames._
import play.api.libs.json.Json
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.{ApplicationLastUseDate, ApplicationResponse, Collaborator, PaginatedApplicationLastUseResponse}
import uk.gov.hmrc.http.{UserId => _, _}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import com.github.tomakehurst.wiremock.client.WireMock._

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.ThirdPartyApplicationConnectorConfig
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apiplatformjobs.util.UrlEncoding
import uk.gov.hmrc.apiplatformjobs.models.ApplicationId
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import java.util.UUID
import scala.util.Random
import uk.gov.hmrc.apiplatformjobs.models.ApplicationUsageDetails
import uk.gov.hmrc.apiplatformjobs.models.UserId


class ThirdPartyApplicationConnectorSpec
  extends AsyncHmrcSpec
  with RepsonseUtils
  with GuiceOneAppPerSuite
  with WiremockSugar
  with UrlEncoding {

  override def fakeApplication(): Application =
  GuiceApplicationBuilder()
    .configure("metrics.jvm" -> false)
    .build()

  class Setup(proxyEnabled: Boolean = false) {
    implicit val hc = HeaderCarrier()
    val apiKeyTest = "5bb51bca-8f97-4f2b-aee4-81a4a70a42d3"
    val bearer = "TestBearerToken"
    val authorisationKeyTest = "TestAuthorisationKey"

    val mockConfig = mock[ThirdPartyApplicationConnectorConfig]
    when(mockConfig.applicationProductionBaseUrl).thenReturn(wireMockUrl)
    when(mockConfig.applicationProductionUseProxy).thenReturn(proxyEnabled)
    when(mockConfig.applicationProductionBearerToken).thenReturn("TestBearerToken")
    when(mockConfig.applicationProductionApiKey).thenReturn(apiKeyTest)
    when(mockConfig.productionAuthorisationKey).thenReturn(authorisationKeyTest)

    val connector = new ProductionThirdPartyApplicationConnector(
      mockConfig,
      app.injector.instanceOf[HttpClient],
      app.injector.instanceOf[ProxiedHttpClient]
    )
  }

  "fetchApplicationsByUserId" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters.formatApplicationResponse

    val userId = UserId.random
    val applicationResponses = List(ApplicationResponse("app id 1"), ApplicationResponse("app id 2"))

    "return application Ids" in new Setup {
      stubFor(
        get(urlPathEqualTo("/developer/applications"))
        .withQueryParam("developerId", equalTo(userId.asQueryParam))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(applicationResponses)
          )
      )

      val result = await(connector.fetchApplicationsByUserId(userId))

      result.size shouldBe 2
      result should contain allOf ("app id 1", "app id 2")
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        get(urlPathEqualTo("/developer/applications"))
        .withQueryParam("developerId", equalTo(userId.asQueryParam))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      intercept[UpstreamErrorResponse] {
        await(connector.fetchApplicationsByUserId(userId))
      }.statusCode shouldBe NOT_FOUND
    }
  }

  "removeCollaborator" should {
    import ThirdPartyApplicationConnector.DeleteCollaboratorRequest
    import ThirdPartyApplicationConnector.JsonFormatters._

    val appId = ApplicationId.random
    val email = "example.com"

    "remove collaborator" in new Setup {
      val request = DeleteCollaboratorRequest(email, adminsToEmail = Set(), notifyCollaborator = false)
      stubFor(
        post(urlPathEqualTo(s"/application/${appId.value}/collaborator/delete"))
        .withJsonRequestBody(request)
        .willReturn(
          aResponse()
            .withStatus(OK)
        )
      )

      val result = await(connector.removeCollaborator(appId.value.toString(), email))

      result shouldBe OK
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        post(urlPathEqualTo(s"/application/${appId.value}/collaborator/delete"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      intercept[UpstreamErrorResponse] {
        await(connector.removeCollaborator(appId.value.toString(), email))
      }.statusCode shouldBe INTERNAL_SERVER_ERROR
    }
  }

  "applicationsLastUsedBefore" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters.formatPaginatedApplicationLastUseDate

    def paginatedResponse(lastUseDates: List[ApplicationLastUseDate]) =
      PaginatedApplicationLastUseResponse(lastUseDates, 1, 100, lastUseDates.size, lastUseDates.size)

    val dateFormatter: DateTimeFormatter = ISODateTimeFormat.dateTime()

    "return application details as ApplicationUsageDetails objects" in new Setup {
      val lastUseDate = DateTime.now.minusMonths(12)
      val dateString: String = dateFormatter.withZoneUTC().print(lastUseDate)

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

      stubFor(
        get(urlPathEqualTo("/applications"))
        .withQueryParam("lastUseBefore", equalTo(encode(dateString)))
        .withQueryParam("sort", equalTo("NO_SORT"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(paginatedResponse(List(oldApplication1, oldApplication2)))
          )
      )

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
      val dateString: String = dateFormatter.withZoneUTC().print(lastUseDate)

      stubFor(
        get(urlPathEqualTo("/applications"))
        .withQueryParam("lastUseBefore", equalTo(encode(dateString)))
        .withQueryParam("sort", equalTo("NO_SORT"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(paginatedResponse(List.empty))
          )
      )

      val results = await(connector.applicationsLastUsedBefore(lastUseDate))

      results.size should be (0)
    }
  }

  "deleteApplication" should {
    "return true if call to TPA is successful" in new Setup {
      val applicationId = ApplicationId.random

      stubFor(
        post(urlPathEqualTo(s"/application/${applicationId.value}/delete"))
          .willReturn(
            aResponse()
              .withStatus(NO_CONTENT)
          )
      )

      val response = await(connector.deleteApplication(applicationId.value))

      response should be (true)
    }

    "include authorisationKey in the authorisation header" in new Setup {
      val applicationId = ApplicationId.random
      val authorisationKeyAsItAppearsOnTheWire = connector.authorisationKey

      stubFor(
        post(urlPathEqualTo(s"/application/${applicationId.value}/delete"))
          .withHeader(AUTHORIZATION, equalTo(authorisationKeyAsItAppearsOnTheWire))
          .willReturn(
            aResponse()
              .withStatus(NO_CONTENT)
          )
      )

      val response = await(connector.deleteApplication(applicationId.value))

      response should be (true)
    }

    "return false if call to TPA fails" in new Setup {
      val applicationId = ApplicationId.random

      stubFor(
        post(urlPathEqualTo(s"/application/${applicationId.value}/delete"))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
          )
      )

      val response = await(connector.deleteApplication(applicationId.value))

      response should be (false)
    }
  }

  "JsonFormatters" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._

    "correctly parse PaginatedApplicationLastUseResponse from TPA" in new PaginatedTPAResponse {
      val parsedResponse: PaginatedApplicationLastUseResponse = Json.fromJson[PaginatedApplicationLastUseResponse](Json.parse(response)).get

      parsedResponse.page should be (pageNumber)
      parsedResponse.pageSize should be (pageSize)
      parsedResponse.total should be (totalApplications)
      parsedResponse.matching should be (matchingApplications)

      parsedResponse.applications.size should be (1)
      val application = parsedResponse.applications.head
      application.id should be (applicationId)
      application.name should be (applicationName)
      application.createdOn should be (createdOn)
      application.lastAccess should be (Some(lastAccess))

      application.collaborators.size should be (2)
      application.collaborators should contain (Collaborator(adminEmailAddress, "ADMINISTRATOR"))
      application.collaborators should contain (Collaborator(developerEmailAddress, "DEVELOPER"))
    }
  }

  "toDomain" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.toDomain

    "correctly convert ApplicationLastUseDates to ApplicationUsageDetails" in new PaginatedTPAResponse {
      val parsedResponse: PaginatedApplicationLastUseResponse = Json.fromJson[PaginatedApplicationLastUseResponse](Json.parse(response)).get

      val convertedApplicationDetails = toDomain(parsedResponse.applications)

      convertedApplicationDetails.size should be (1)
      val convertedApplication = convertedApplicationDetails.head

      convertedApplication.applicationId should be (applicationId)
      convertedApplication.applicationName should be (applicationName)
      convertedApplication.creationDate should be (createdOn)
      convertedApplication.lastAccessDate should be (Some(lastAccess))
      convertedApplication.administrators.size should be (1)
      convertedApplication.administrators should contain (adminEmailAddress)
      convertedApplication.administrators should not contain (developerEmailAddress)
    }
  }

  trait PaginatedTPAResponse {
    val applicationId = UUID.randomUUID()
    val applicationName = Random.alphanumeric.take(10).mkString
    val createdOn = DateTime.now.minusYears(1)
    val lastAccess = createdOn.plusDays(5)

    val pageNumber = 1
    val pageSize = 25
    val totalApplications = 500
    val matchingApplications = 1

    val adminEmailAddress = "admin@foo.com"
    val developerEmailAddress = "developer@foo.com"

    val response = s"""{
                      |  "applications": [
                      |    {
                      |      "id": "${applicationId.toString}",
                      |      "name": "$applicationName",
                      |      "collaborators": [
                      |        {
                      |          "emailAddress": "$adminEmailAddress",
                      |          "role": "ADMINISTRATOR"
                      |        },
                      |        {
                      |          "emailAddress": "$developerEmailAddress",
                      |          "role": "DEVELOPER"
                      |        }
                      |      ],
                      |      "createdOn": ${createdOn.getMillis},
                      |      "lastAccess": ${lastAccess.getMillis}
                      |    }
                      |  ],
                      |  "page": $pageNumber,
                      |  "pageSize": $pageSize,
                      |  "total": $totalApplications,
                      |  "matching": $matchingApplications
                      |}""".stripMargin
  }
}
