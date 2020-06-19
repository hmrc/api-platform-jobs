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

import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apiplatformjobs.models.APIDefinition
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful

class ApiPlatformMicroserviceConnectorSpec extends UnitSpec with ScalaFutures with MockitoSugar {

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockHttp: HttpClient = mock[HttpClient]
    val baseUrl = "http://api-platform-microservice"
    val config = ApiPlatformMicroserviceConnectorConfig(baseUrl)
    val devEmail = "joe.bloggs@example.com"

    def endpoint(path: String) = s"$baseUrl/$path"

    val connector = new ApiPlatformMicroserviceConnector(config, mockHttp)

  }

  "fetchAPIDefinitionsForCollaborator" should {
    "fetch api definitions" in new Setup {

      val expectedAPIDefinitions = Seq(APIDefinition("Agent Authorisation", Seq("AGENTS")))

      when(mockHttp.GET[Seq[APIDefinition]](meq(endpoint("combined-api-definitions")),
        meq(Seq("collaboratorEmail" -> devEmail)))(any(), any(), any()))
        .thenReturn(successful(expectedAPIDefinitions))

      val result: Seq[APIDefinition] = await(connector.fetchApiDefinitionsForCollaborator(devEmail))

      result shouldBe expectedAPIDefinitions
    }

    "propagate error when endpoint returns error" in new Setup {
      when(mockHttp.GET[Seq[APIDefinition]](meq(endpoint("combined-api-definitions")), any())(any(), any(), any()))
        .thenReturn(Future.failed(new NotFoundException("")))

      intercept[NotFoundException] {
        await(connector.fetchApiDefinitionsForCollaborator(devEmail))
      }
    }
  }
}
