/*
 * Copyright 2026 HM Revenue & Customs
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

import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import play.api.Application
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{OrganisationId, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.organisations.domain
import uk.gov.hmrc.apiplatform.modules.organisations.domain.models.{Organisation, OrganisationName}

import uk.gov.hmrc.apiplatformjobs.connectors.OrganisationConnector.RemoveMemberRequest
import uk.gov.hmrc.apiplatformjobs.utils.{AsyncHmrcSpec, UrlEncoding}

class OrganisationConnectorSpec extends AsyncHmrcSpec with ResponseUtils with GuiceOneAppPerSuite with WiremockSugar with UrlEncoding with FixedClock {

  val userId         = UserId.random
  val emailAddress   = "bob@example.com".toLaxEmail
  val organisationId = OrganisationId.random
  val collaborator   = domain.models.Collaborator(domain.models.Collaborator.Roles.Administrator, userId)
  val organisation   = Organisation(organisationId, OrganisationName("Org name"), Organisation.OrganisationType.UkLimitedCompany, instant, Set(collaborator))

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  class Setup(proxyEnabled: Boolean = false) {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val mockConfig = mock[OrganisationConnector.Config]
    when(mockConfig.serviceBaseUrl).thenReturn(wireMockUrl)

    val connector = new OrganisationConnector(
      app.injector.instanceOf[HttpClientV2],
      mockConfig
    )
  }

  "OrganisationConnector" when {

    "fetchOrganisationsByUserId" should {
      "call fetchOrganisationsByUserId successfully" in new Setup {
        stubFor(
          get(urlPathEqualTo(s"/organisation/user/$userId"))
            .willReturn(
              aResponse()
                .withJsonBody(List(organisation))
                .withStatus(OK)
            )
        )
        await(connector.fetchOrganisationsByUserId(userId)) shouldBe List(organisation)
      }

      "propagate error when endpoint returns error" in new Setup {
        stubFor(
          get(urlPathEqualTo(s"/organisation/user/$userId"))
            .willReturn(
              aResponse()
                .withStatus(INTERNAL_SERVER_ERROR)
            )
        )
        intercept[UpstreamErrorResponse] {
          await(connector.fetchOrganisationsByUserId(userId))
        }.statusCode shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "removeCollaboratorFromOrganisation" should {
      "call removeCollaboratorFromOrganisation successfully" in new Setup {
        stubFor(
          delete(urlPathEqualTo(s"/organisation/${organisationId.value}/member/$userId"))
            .withJsonRequestBody(RemoveMemberRequest(userId, emailAddress))
            .willReturn(
              aResponse()
                .withJsonBody(organisation)
                .withStatus(OK)
            )
        )
        await(connector.removeCollaboratorFromOrganisation(organisationId, userId, emailAddress)) shouldBe Right(organisation)
      }

      "propagate error when endpoint returns error" in new Setup {
        stubFor(
          delete(urlPathEqualTo(s"/organisation/${organisationId.value}/member/$userId"))
            .willReturn(
              aResponse()
                .withStatus(INTERNAL_SERVER_ERROR)
            )
        )
        val result = await(connector.removeCollaboratorFromOrganisation(organisationId, userId, emailAddress))
        result shouldBe Left(s"Failed to remove user $userId from organisation $organisationId")
      }
    }
  }
}
