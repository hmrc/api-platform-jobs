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

// import java.time.LocalDateTime
// import scala.concurrent.ExecutionContext.Implicits.global

// import cats.data.NonEmptyList
// import com.github.tomakehurst.wiremock.client.WireMock._
// import org.scalatestplus.play.guice.GuiceOneAppPerSuite

// import play.api.Application
// import play.api.http.Status._
// import play.api.inject.guice.GuiceApplicationBuilder
// import play.api.libs.json.Json
// import uk.gov.hmrc.http.client.HttpClientV2
// import uk.gov.hmrc.http.{BadRequestException, HeaderCarrier, InternalServerException}

// import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborators
// import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommands, CommandFailure, CommandFailures, DispatchRequest}
// import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
// import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, UserId}
// import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

// import uk.gov.hmrc.apiplatformjobs.models.HasSucceeded
// import uk.gov.hmrc.apiplatformjobs.utils.{AsyncHmrcSpec, UrlEncoding}

// class ApplicationCommandConnectorSpec extends AsyncHmrcSpec with ResponseUtils with GuiceOneAppPerSuite with WiremockSugar with UrlEncoding with FixedClock {

//   val appId        = ApplicationId.random
//   val actor        = Actors.ScheduledJob("TestMe")
//   val timestamp    = instant
//   val userId       = UserId.random
//   val emailAddress = "bob@example.com".toLaxEmail
//   val collaborator = Collaborators.Developer(userId, emailAddress)

//   override def fakeApplication(): Application =
//     GuiceApplicationBuilder()
//       .configure("metrics.jvm" -> false)
//       .build()

//   class Setup(proxyEnabled: Boolean = false) {
//     implicit val hc: HeaderCarrier = HeaderCarrier()
//     val apiKeyTest                 = "5bb51bca-8f97-4f2b-aee4-81a4a70a42d3"
//     val bearer                     = "TestBearerToken"
//     val authorisationKeyTest       = "TestAuthorisationKey"

//     val mockConfig = mock[ThirdPartyApplicationConnector.ThirdPartyApplicationConnectorConfig]
//     when(mockConfig.productionBaseUrl).thenReturn(wireMockUrl)
//     when(mockConfig.productionAuthorisationKey).thenReturn(authorisationKeyTest)

//     val connector = new ProductionApplicationCommandConnector(
//       app.injector.instanceOf[HttpClientV2],
//       mockConfig
//     )
//   }

//   "ApplicationCommandConnector" when {
//     "dispatch request" should {
//       val anAdminEmail          = "admin@example.com".toLaxEmail
//       val developerCollaborator = Collaborators.Developer(UserId.random, "dev@example.com".toLaxEmail)
//       val jsonText              =
//         s"""{"command":{"actor":{"email":"${anAdminEmail.text}","actorType":"COLLABORATOR"},"collaborator":{"userId":"${developerCollaborator.userId.value}","emailAddress":"dev@example.com","role":"DEVELOPER"},"timestamp":"2020-01-01T12:00:00.000Z","updateType":"removeCollaborator"},"verifiedCollaboratorsToNotify":["admin@example.com"]}"""
//       val timestamp             = LocalDateTime.of(2020, 1, 1, 12, 0, 0).asInstant
//       val cmd                   = ApplicationCommands.RemoveCollaborator(Actors.AppCollaborator(anAdminEmail), developerCollaborator, timestamp)
//       val req                   = DispatchRequest(cmd, Set(anAdminEmail))
//       import cats.syntax.option._

//       "write to json" in {
//         Json.toJson(req).toString() shouldBe jsonText
//       }
//       "read from json" in {
//         Json.parse(jsonText).asOpt[DispatchRequest] shouldBe req.some
//       }
//     }

//     "dispatch request with no emails" should {
//       val developerCollaborator = Collaborators.Developer(UserId.random, "dev@example.com".toLaxEmail)
//       val jsonText              =
//         s"""{"command":{"actor":{"jobId":"BOB","actorType":"SCHEDULED_JOB"},"collaborator":{"userId":"${developerCollaborator.userId.value}","emailAddress":"dev@example.com","role":"DEVELOPER"},"timestamp":"2020-01-01T12:00:00.000Z","updateType":"removeCollaborator"},"verifiedCollaboratorsToNotify":[]}"""
//       val timestamp             = LocalDateTime.of(2020, 1, 1, 12, 0, 0).asInstant
//       val cmd                   = ApplicationCommands.RemoveCollaborator(Actors.ScheduledJob("BOB"), developerCollaborator, timestamp)
//       val req                   = DispatchRequest(cmd, Set.empty)
//       import cats.syntax.option._

//       "write to json" in {
//         Json.toJson(req).toString() shouldBe jsonText
//       }
//       "read from json" in {
//         Json.parse(jsonText).asOpt[DispatchRequest] shouldBe req.some
//       }
//     }

//     "dispatch" should {
//       val command = ApplicationCommands.RemoveCollaborator(actor, collaborator, timestamp)
//       val request = DispatchRequest(command, Set.empty)

//       "removeCollaborator" in new Setup {
//         stubFor(
//           patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
//             .withJsonRequestBody(request)
//             .willReturn(
//               aResponse()
//                 .withStatus(OK)
//             )
//         )
//         await(connector.dispatch(appId, command, Set.empty)) shouldBe (HasSucceeded)
//       }

//       "throw when a command fails" in new Setup {
//         val errors = NonEmptyList.one[CommandFailure](CommandFailures.CannotRemoveLastAdmin)
//         import uk.gov.hmrc.apiplatform.modules.common.services.NonEmptyListFormatters._

//         stubFor(
//           patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
//             .willReturn(
//               aResponse()
//                 .withStatus(BAD_REQUEST)
//                 .withJsonBody(errors)
//             )
//         )

//         intercept[BadRequestException] {
//           await(connector.dispatch(appId, command, Set.empty))
//         }
//       }

//       "throw when a command fails and failures are unparseable" in new Setup {
//         stubFor(
//           patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
//             .willReturn(
//               aResponse()
//                 .withStatus(BAD_REQUEST)
//                 .withJsonBody("{}")
//             )
//         )

//         intercept[InternalServerException] {
//           await(connector.dispatch(appId, command, Set.empty))
//         }
//       }

//       "throw exception when endpoint returns internal server error" in new Setup {
//         stubFor(
//           patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
//             .willReturn(
//               aResponse()
//                 .withStatus(INTERNAL_SERVER_ERROR)
//             )
//         )

//         intercept[InternalServerException] {
//           await(connector.dispatch(appId, command, Set.empty))
//         }
//       }
//     }
//   }
// }
