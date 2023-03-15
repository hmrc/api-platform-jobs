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

import cats.data.NonEmptyList
import cats.syntax.either._
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import play.api.Application
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, InternalServerException}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, Collaborators}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{CommandFailure, CommandFailures, DispatchRequest, RemoveCollaborator}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId

import uk.gov.hmrc.apiplatformjobs.util.{AsyncHmrcSpec, UrlEncoding}

class ApplicationCommandConnectorSpec extends AsyncHmrcSpec with RepsonseUtils with GuiceOneAppPerSuite with WiremockSugar with UrlEncoding {

  val appId        = ApplicationId.random
  val actor        = Actors.ScheduledJob("TestMe")
  val timestamp    = LocalDateTime.now()
  val userId       = UserId.random
  val emailAddress = "bob@example.com".toLaxEmail
  val collaborator = Collaborators.Developer(userId, emailAddress)

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  class Setup(proxyEnabled: Boolean = false) {
    implicit val hc          = HeaderCarrier()
    val apiKeyTest           = "5bb51bca-8f97-4f2b-aee4-81a4a70a42d3"
    val bearer               = "TestBearerToken"
    val authorisationKeyTest = "TestAuthorisationKey"

    val mockConfig = mock[ThirdPartyApplicationConnector.ThirdPartyApplicationConnectorConfig]
    when(mockConfig.productionBaseUrl).thenReturn(wireMockUrl)
    when(mockConfig.productionAuthorisationKey).thenReturn(authorisationKeyTest)

    val connector = new ProductionApplicationCommandConnector(
      app.injector.instanceOf[HttpClient],
      mockConfig
    )
  }

  "dispatch" should {
    val command = RemoveCollaborator(actor, collaborator, timestamp)
    val request = DispatchRequest(command, Set.empty)

    "removeCollaborator" in new Setup {
      stubFor(
        patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
          .withJsonRequestBody(request)
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )

      val result = await(connector.dispatch(appId, command, Set.empty))

      result shouldBe ().asRight[NonEmptyList[CommandFailure]]
    }

    "propagate error when endpoint returns bad request" in new Setup {
      val errors = NonEmptyList.one[CommandFailure](CommandFailures.CannotRemoveLastAdmin)
      import uk.gov.hmrc.apiplatform.modules.common.services.NonEmptyListFormatters._
      import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailureJsonFormatters._

      stubFor(
        patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
              .withJsonBody(errors)
          )
      )

      await(connector.dispatch(appId, command, Set.empty)) shouldBe errors.asLeft[Unit]
    }

    "throw exception when endpoint returns internal server error" in new Setup {
      stubFor(
        patch(urlPathEqualTo(s"/application/${appId.value}/dispatch"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      intercept[InternalServerException] {
        await(connector.dispatch(appId, command, Set.empty))
      }
    }
  }

}
