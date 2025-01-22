/*
 * Copyright 2025 HM Revenue & Customs
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

// /*
//  * Copyright 2023 HM Revenue & Customs
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  */

// package uk.gov.hmrc.apiplatformjobs.connectors

// import java.time.format.DateTimeFormatter
// import java.time.temporal.ChronoUnit.DAYS
// import java.time.{Instant, LocalDateTime, ZoneOffset}
// import scala.concurrent.ExecutionContext.Implicits.global

// import com.github.tomakehurst.wiremock.client.WireMock._
// import org.scalatestplus.play.guice.GuiceOneAppPerSuite

// import play.api.Application
// import play.api.http.Status._
// import play.api.inject.guice.GuiceApplicationBuilder
// import play.api.libs.json.Json
// import uk.gov.hmrc.http._
// import uk.gov.hmrc.http.client.HttpClientV2

// import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
// import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
// import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, UserId}

// import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector._
// import uk.gov.hmrc.apiplatformjobs.models._
// import uk.gov.hmrc.apiplatformjobs.utils.{AsyncHmrcSpec, UrlEncoding}

// class ThirdPartyApplicationConnectorSpec
//     extends AsyncHmrcSpec
//     with ResponseUtils
//     with GuiceOneAppPerSuite
//     with WiremockSugar
//     with UrlEncoding
//     with ApplicationWithCollaboratorsFixtures {

//   override def fakeApplication(): Application =
//     GuiceApplicationBuilder()
//       .configure("metrics.jvm" -> false)
//       .build()

//   class Setup(proxyEnabled: Boolean = false) {
//     implicit val hc: HeaderCarrier = HeaderCarrier()
//     val apiKeyTest                 = "5bb51bca-8f97-4f2b-aee4-81a4a70a42d3"
//     val bearer                     = "TestBearerToken"
//     val authorisationKeyTest       = "TestAuthorisationKey"

//     val mockConfig = mock[ThirdPartyApplicationConnectorConfig]
//     when(mockConfig.productionBaseUrl).thenReturn(wireMockUrl)
//     when(mockConfig.productionAuthorisationKey).thenReturn(authorisationKeyTest)

//     val connector = new ProductionThirdPartyApplicationConnector(
//       mockConfig,
//       app.injector.instanceOf[HttpClientV2]
//     )
//   }

//   "fetchApplicationsByUserId" should {

//     val appAADUsers = Set[Collaborator](adminOne, adminTwo, developerOne)
//     val appADUsers  = Set[Collaborator](adminOne, developerOne)

//     val app1 = standardApp.withCollaborators(appAADUsers)
//     val app2 = standardApp2.withCollaborators(appADUsers)

//     val applicationResponses = List(app1, app2)

//     "return application Ids" in new Setup {
//       stubFor(
//         get(urlPathEqualTo(s"/developer/${adminOne.userId}/applications"))
//           .willReturn(
//             aResponse()
//               .withStatus(OK)
//               .withJsonBody(applicationResponses)
//           )
//       )

//       val result = await(connector.fetchApplicationsByUserId(adminOne.userId))

//       result.size shouldBe 2
//       result.map(_.id) should contain.allOf(standardApp.id, standardApp2.id)
//     }

//     "propagate error when endpoint returns error" in new Setup {
//       stubFor(
//         get(urlPathEqualTo(s"/developer/${adminOne.userId}/applications"))
//           .willReturn(
//             aResponse()
//               .withStatus(NOT_FOUND)
//           )
//       )

//       intercept[UpstreamErrorResponse] {
//         await(connector.fetchApplicationsByUserId(adminOne.userId))
//       }.statusCode shouldBe NOT_FOUND
//     }
//   }

//   "applicationsLastUsedBefore" should {
//     def paginatedResponse(lastUseDates: List[ApplicationWithCollaborators]) =
//       PaginatedApplications(lastUseDates, 1, 100, lastUseDates.size, lastUseDates.size)

//     val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME
//     val deleteRestriction                = DeleteRestrictionType.NO_RESTRICTION

//     "return application details as ApplicationUsageDetails objects" in new Setup {
//       val lastUseLDT         = now.minusMonths(12)
//       val lastUseDate        = lastUseLDT.asInstant
//       val dateString: String = dateFormatter.format(lastUseLDT)

//       val oldApplication1Admin = "foo@bar.com".toLaxEmail
//       val oldApplication1      = standardApp.withCollaborators(
//         Collaborators.Administrator(UserId.random, oldApplication1Admin),
//         Collaborators.Developer(UserId.random, "a@b.com".toLaxEmail)
//       )
//         .modify(
//           _.copy(
//             createdOn = LocalDateTime.now.minusMonths(12).toInstant(ZoneOffset.UTC),
//             lastAccess = Some(LocalDateTime.now.minusMonths(13).toInstant(ZoneOffset.UTC))
//           )
//         )
//       val oldApplication2Admin = "bar@baz.com".toLaxEmail

//       val oldApplication2 = standardApp.withCollaborators(
//         Collaborators.Administrator(UserId.random, oldApplication2Admin),
//         Collaborators.Developer(UserId.random, "b@c.com".toLaxEmail)
//       )
//         .modify(
//           _.copy(
//             createdOn = LocalDateTime.now.minusMonths(12).toInstant(ZoneOffset.UTC),
//             lastAccess = Some(LocalDateTime.now.minusMonths(14).toInstant(ZoneOffset.UTC))
//           )
//         )

//       stubFor(
//         get(urlPathEqualTo("/applications"))
//           .withQueryParam("lastUseBefore", equalTo(dateString))
//           .withQueryParam("deleteRestriction", equalTo(deleteRestriction.toString))
//           .withQueryParam("sort", equalTo("NO_SORT"))
//           .willReturn(
//             aResponse()
//               .withStatus(OK)
//               .withJsonBody(paginatedResponse(List(oldApplication1, oldApplication2)))
//           )
//       )

//       val results = await(connector.applicationSearch(Some(lastUseDate), deleteRestriction))

//       results should contain
//       ApplicationUsageDetails(oldApplication1.id, oldApplication1.name, Set(oldApplication1Admin), oldApplication1.details.createdOn, oldApplication1.details.lastAccess)
//       results should contain
//       ApplicationUsageDetails(oldApplication2.id, oldApplication2.name, Set(oldApplication2Admin), oldApplication2.details.createdOn, oldApplication2.details.lastAccess)
//     }

//     "return empty Sequence when no results are returned" in new Setup {
//       val lastUseLDT         = now.minusMonths(12)
//       val lastUseDate        = lastUseLDT.asInstant
//       val dateString: String = dateFormatter.format(lastUseLDT)

//       stubFor(
//         get(urlPathEqualTo("/applications"))
//           .withQueryParam("lastUseBefore", equalTo(dateString))
//           .withQueryParam("deleteRestriction", equalTo(deleteRestriction.toString))
//           .withQueryParam("sort", equalTo("NO_SORT"))
//           .willReturn(
//             aResponse()
//               .withStatus(OK)
//               .withJsonBody(paginatedResponse(List.empty))
//           )
//       )

//       val results = await(connector.applicationSearch(Some(lastUseDate), deleteRestriction))

//       results.size should be(0)
//     }

//     "ensure lastUseBefore is not in query param when lastUseDate is None" in new Setup {
//       stubFor(
//         get(urlPathEqualTo("/applications"))
//           .withQueryParam("deleteRestriction", equalTo(deleteRestriction.toString))
//           .withQueryParam("sort", equalTo("NO_SORT"))
//           .willReturn(
//             aResponse()
//               .withStatus(OK)
//               .withJsonBody(paginatedResponse(List.empty))
//           )
//       )

//       val results = await(connector.applicationSearch(None, deleteRestriction))

//       results.size should be(0)
//     }
//   }

//   "deleteApplication" should {
//     "return true if call to TPA is successful" in new Setup {
//       val applicationId = ApplicationId.random

//       stubFor(
//         patch(urlPathEqualTo(s"/application/${applicationId.value}"))
//           .willReturn(
//             aResponse()
//               .withStatus(NO_CONTENT)
//           )
//       )

//       val response = await(connector.deleteApplication(applicationId, "jobId", "reasons", Instant.now))

//       response should be(ApplicationUpdateSuccessResult)
//     }

//     "return false if call to TPA fails" in new Setup {
//       val applicationId = ApplicationId.random

//       stubFor(
//         patch(urlPathEqualTo(s"/application/${applicationId.value}"))
//           .willReturn(
//             aResponse()
//               .withStatus(BAD_REQUEST)
//           )
//       )

//       val response = await(connector.deleteApplication(applicationId, "jobId", "reasons", Instant.now))

//       response should be(ApplicationUpdateFailureResult)
//     }
//   }

//   "JsonFormatters" should {
//     "correctly parse PaginatedApplicationLastUseResponse from TPA" in new PaginatedTPAResponse {
//       val parsedResponse: PaginatedApplications = Json.fromJson[PaginatedApplications](Json.parse(response)).get

//       parsedResponse.page should be(pageNumber)
//       parsedResponse.pageSize should be(pageSize)
//       parsedResponse.total should be(totalApplications)
//       parsedResponse.matching should be(matchingApplications)

//       parsedResponse.applications.size should be(1)
//       val application = parsedResponse.applications.head
//       application.id should be(applicationId)
//       application.name should be(applicationName)
//       application.details.createdOn should be(createdOn)
//       application.details.lastAccess should be(Some(lastAccess))

//       application.collaborators.size should be(3)
//       application.collaborators should contain(Collaborators.Administrator(adminUserId, adminEmailAddress))
//       application.collaborators should contain(Collaborators.Developer(developerUserId, developerEmailAddress))
//     }
//   }

//   "toDomain" should {
//     import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.toDomain

//     "correctly convert ApplicationLastUseDates to ApplicationUsageDetails" in new PaginatedTPAResponse {
//       val parsedResponse: PaginatedApplications = Json.fromJson[PaginatedApplications](Json.parse(response)).get

//       val convertedApplicationDetails = toDomain(parsedResponse.applications)

//       convertedApplicationDetails.size should be(1)
//       val convertedApplication = convertedApplicationDetails.head

//       convertedApplication.applicationId should be(applicationId)
//       convertedApplication.applicationName should be(applicationName)
//       convertedApplication.creationDate should be(createdOn)
//       convertedApplication.lastAccessDate should be(Some(lastAccess))
//       convertedApplication.administrators.size should be(2)
//       convertedApplication.administrators should contain(adminEmailAddress)
//       convertedApplication.administrators should not contain (developerEmailAddress)
//     }
//   }

//   trait PaginatedTPAResponse {
//     val applicationId   = standardApp.id
//     val applicationName = standardApp.name
//     val createdOn       = now.minusYears(1).asInstant
//     val lastAccess      = createdOn.plus(5, DAYS)

//     val pageNumber           = 1
//     val pageSize             = 25
//     val totalApplications    = 500
//     val matchingApplications = 1

//     val adminEmailAddress     = standardApp.admins.head.emailAddress
//     val adminUserId           = standardApp.admins.head.userId
//     val developerEmailAddress = standardApp.collaborators.filter(_.isDeveloper).head.emailAddress
//     val developerUserId       = standardApp.collaborators.filter(_.isDeveloper).head.userId

//     val appJson = Json.toJson(standardApp.modify(_.copy(createdOn = createdOn, lastAccess = Some(lastAccess)))).toString()

//     val response = s"""{
//                       |  "applications": [
//                       |    $appJson
//                       |  ],
//                       |  "page": $pageNumber,
//                       |  "pageSize": $pageSize,
//                       |  "total": $totalApplications,
//                       |  "matching": $matchingApplications
//                       |}""".stripMargin
//   }
// }
