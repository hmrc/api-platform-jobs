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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
import uk.gov.hmrc.apiplatformjobs.models.{ApplicationId, ApplicationUpdateFailureResult, ApplicationUpdateSuccessResult, ApplicationUsageDetails, UserId}
import uk.gov.hmrc.apiplatformjobs.util.{AsyncHmrcSpec, UrlEncoding}
import uk.gov.hmrc.http._

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.MILLIS
import java.time.{LocalDateTime, ZoneOffset}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

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
        get(urlPathEqualTo(s"/developer/${userId}/applications"))
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
        get(urlPathEqualTo(s"/developer/${userId}/applications"))
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

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME

    "return application details as ApplicationUsageDetails objects" in new Setup {
      val lastUseDate = LocalDateTime.now.minusMonths(12)
      val dateString: String = dateFormatter.format(lastUseDate)

      val oldApplication1Admin: String = "foo@bar.com"
      val oldApplication1 =
        ApplicationLastUseDate(
          UUID.randomUUID(),
          Random.alphanumeric.take(10).mkString,
          Set(Collaborator(oldApplication1Admin, "ADMINISTRATOR"), Collaborator("a@b.com", "DEVELOPER")),
          LocalDateTime.now.minusMonths(12),
          Some(LocalDateTime.now.minusMonths(13)))
      val oldApplication2Admin: String = "bar@baz.com"
      val oldApplication2 =
        ApplicationLastUseDate(
          UUID.randomUUID(),
          Random.alphanumeric.take(10).mkString,
          Set(Collaborator(oldApplication2Admin, "ADMINISTRATOR"), Collaborator("b@c.com", "DEVELOPER")),
         LocalDateTime.now.minusMonths(12),
          Some(LocalDateTime.now.minusMonths(14)))

      stubFor(
        get(urlPathEqualTo("/applications"))
        .withQueryParam("lastUseBefore", equalTo(dateString))
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
      val lastUseDate =LocalDateTime.now.minusMonths(12)
      val dateString: String = dateFormatter.format(lastUseDate)

      stubFor(
        get(urlPathEqualTo("/applications"))
        .withQueryParam("lastUseBefore", equalTo(dateString))
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
        patch(urlPathEqualTo(s"/application/${applicationId.value}"))
          .willReturn(
            aResponse()
              .withStatus(NO_CONTENT)
          )
      )

      val response = await(connector.deleteApplication(applicationId.value, "jobId", "reasons", LocalDateTime.now))

      response should be (ApplicationUpdateSuccessResult)
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

      val response = await(connector.deleteApplication(applicationId.value, "jobId", "reasons", LocalDateTime.now))

      response should be (ApplicationUpdateFailureResult)
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
    val createdOn =LocalDateTime.now(fixedClock).minusYears(1).truncatedTo(MILLIS)
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
                      |      "createdOn": ${createdOn.toInstant(ZoneOffset.UTC).toEpochMilli},
                      |      "lastAccess": ${lastAccess.toInstant(ZoneOffset.UTC).toEpochMilli}
                      |    }
                      |  ],
                      |  "page": $pageNumber,
                      |  "pageSize": $pageSize,
                      |  "total": $totalApplications,
                      |  "matching": $matchingApplications
                      |}""".stripMargin
  }
}
