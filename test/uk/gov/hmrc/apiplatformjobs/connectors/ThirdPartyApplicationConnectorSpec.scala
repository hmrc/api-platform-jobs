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

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit.MILLIS
import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import uk.gov.hmrc.http._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, Collaborator, Collaborators}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.util.{AsyncHmrcSpec, UrlEncoding}

class ThirdPartyApplicationConnectorSpec extends AsyncHmrcSpec with RepsonseUtils with GuiceOneAppPerSuite with WiremockSugar with UrlEncoding {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  class Setup(proxyEnabled: Boolean = false) {
    implicit val hc          = HeaderCarrier()
    val apiKeyTest           = "5bb51bca-8f97-4f2b-aee4-81a4a70a42d3"
    val bearer               = "TestBearerToken"
    val authorisationKeyTest = "TestAuthorisationKey"

    val mockConfig = mock[ThirdPartyApplicationConnectorConfig]
    when(mockConfig.productionBaseUrl).thenReturn(wireMockUrl)
    when(mockConfig.productionAuthorisationKey).thenReturn(authorisationKeyTest)

    val connector = new ProductionThirdPartyApplicationConnector(
      mockConfig,
      app.injector.instanceOf[HttpClient]
    )
  }

  "fetchApplicationsByUserId" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters.formatApplicationResponse

    val appId1 = ApplicationId.random
    val appId2 = ApplicationId.random

    val userId               = UserId.random
    val user1Id = UserId.random
    val user1Email = "bob@example.com".toLaxEmail
    val user2Id = UserId.random
    val user2Email = "alice@example.com".toLaxEmail
    val user3Id = UserId.random
    val user3Email = "charlie@example.com".toLaxEmail

    val appAADUsers = Set[Collaborator](Collaborators.Administrator(user1Id, user1Email), Collaborators.Administrator(user2Id, user2Email), Collaborators.Developer(user3Id, user3Email))
    val appADUsers = Set[Collaborator](Collaborators.Administrator(user1Id, user1Email), Collaborators.Developer(user2Id, user2Email))

    val app1 = ApplicationResponse(appId1, appAADUsers)
    val app2 = ApplicationResponse(appId2, appADUsers)

    val applicationResponses = List(app1, app2)

    "return application Ids" in new Setup {
      stubFor(
        get(urlPathEqualTo(s"/developer/${user1Id.asText}/applications"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(applicationResponses)
          )
      )

      val result = await(connector.fetchApplicationsByUserId(user1Id))

      result.size shouldBe 2
      result.map(_.id) should contain allOf (appId1, appId2)
    }

    "propagate error when endpoint returns error" in new Setup {
      stubFor(
        get(urlPathEqualTo(s"/developer/${userId.asText}/applications"))
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

  "applicationsLastUsedBefore" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters.formatPaginatedApplicationLastUseDate

    def paginatedResponse(lastUseDates: List[ApplicationLastUseDate]) =
      PaginatedApplicationLastUseResponse(lastUseDates, 1, 100, lastUseDates.size, lastUseDates.size)

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME

    "return application details as ApplicationUsageDetails objects" in new Setup {
      val lastUseDate        = LocalDateTime.now.minusMonths(12)
      val dateString: String = dateFormatter.format(lastUseDate)

      val oldApplication1Admin = "foo@bar.com".toLaxEmail
      val oldApplication1      =
        ApplicationLastUseDate(
          ApplicationId.random,
          Random.alphanumeric.take(10).mkString,
          Set(Collaborators.Administrator(UserId.random, oldApplication1Admin), Collaborators.Developer(UserId.random, "a@b.com".toLaxEmail)),
          LocalDateTime.now.minusMonths(12),
          Some(LocalDateTime.now.minusMonths(13))
        )
      val oldApplication2Admin = "bar@baz.com".toLaxEmail
      val oldApplication2      =
        ApplicationLastUseDate(
          ApplicationId.random,
          Random.alphanumeric.take(10).mkString,
          Set(Collaborators.Administrator(UserId.random, oldApplication2Admin), Collaborators.Developer(UserId.random, "b@c.com".toLaxEmail)),
          LocalDateTime.now.minusMonths(12),
          Some(LocalDateTime.now.minusMonths(14))
        )

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
      ApplicationUsageDetails(oldApplication1.id, oldApplication1.name, Set(oldApplication1Admin), oldApplication1.createdOn, oldApplication1.lastAccess)
      results should contain
      ApplicationUsageDetails(oldApplication2.id, oldApplication2.name, Set(oldApplication2Admin), oldApplication2.createdOn, oldApplication2.lastAccess)
    }

    "return empty Sequence when no results are returned" in new Setup {
      val lastUseDate        = LocalDateTime.now.minusMonths(12)
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

      results.size should be(0)
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

      val response = await(connector.deleteApplication(applicationId, "jobId", "reasons", LocalDateTime.now))

      response should be(ApplicationUpdateSuccessResult)
    }

    "return false if call to TPA fails" in new Setup {
      val applicationId = ApplicationId.random

      stubFor(
        patch(urlPathEqualTo(s"/application/${applicationId.value}"))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
          )
      )

      val response = await(connector.deleteApplication(applicationId, "jobId", "reasons", LocalDateTime.now))

      response should be(ApplicationUpdateFailureResult)
    }
  }

  "JsonFormatters" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._

    "correctly parse PaginatedApplicationLastUseResponse from TPA" in new PaginatedTPAResponse {
      val parsedResponse: PaginatedApplicationLastUseResponse = Json.fromJson[PaginatedApplicationLastUseResponse](Json.parse(response)).get

      parsedResponse.page should be(pageNumber)
      parsedResponse.pageSize should be(pageSize)
      parsedResponse.total should be(totalApplications)
      parsedResponse.matching should be(matchingApplications)

      parsedResponse.applications.size should be(1)
      val application = parsedResponse.applications.head
      application.id should be(applicationId)
      application.name should be(applicationName)
      application.createdOn should be(createdOn)
      application.lastAccess should be(Some(lastAccess))

      application.collaborators.size should be(2)
      application.collaborators should contain(Collaborators.Administrator(adminUserId, adminEmailAddress))
      application.collaborators should contain(Collaborators.Developer(developerUserId, developerEmailAddress))
    }
  }

  "toDomain" should {
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.JsonFormatters._
    import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.toDomain

    "correctly convert ApplicationLastUseDates to ApplicationUsageDetails" in new PaginatedTPAResponse {
      val parsedResponse: PaginatedApplicationLastUseResponse = Json.fromJson[PaginatedApplicationLastUseResponse](Json.parse(response)).get

      val convertedApplicationDetails = toDomain(parsedResponse.applications)

      convertedApplicationDetails.size should be(1)
      val convertedApplication = convertedApplicationDetails.head

      convertedApplication.applicationId should be(applicationId)
      convertedApplication.applicationName should be(applicationName)
      convertedApplication.creationDate should be(createdOn)
      convertedApplication.lastAccessDate should be(Some(lastAccess))
      convertedApplication.administrators.size should be(1)
      convertedApplication.administrators should contain(adminEmailAddress)
      convertedApplication.administrators should not contain (developerEmailAddress)
    }
  }

  trait PaginatedTPAResponse {
    val applicationId   = ApplicationId.random
    val applicationName = Random.alphanumeric.take(10).mkString
    val createdOn       = LocalDateTime.now(fixedClock).minusYears(1).truncatedTo(MILLIS)
    val lastAccess      = createdOn.plusDays(5)

    val pageNumber           = 1
    val pageSize             = 25
    val totalApplications    = 500
    val matchingApplications = 1

    val adminEmailAddress     = "admin@foo.com".toLaxEmail
    val adminUserId           = UserId.random
    val developerEmailAddress = "developer@foo.com".toLaxEmail
    val developerUserId       = UserId.random

    val response = s"""{
                      |  "applications": [
                      |    {
                      |      "id": "${applicationId.value.toString()}",
                      |      "name": "$applicationName",
                      |      "collaborators": [
                      |        {
                      |          "userId": "${adminUserId.value}",
                      |          "emailAddress": "${adminEmailAddress.text}",
                      |          "role": "ADMINISTRATOR"
                      |        },
                      |        {
                      |          "userId": "${developerUserId.value}",
                      |          "emailAddress": "${developerEmailAddress.text}",
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
