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

package uk.gov.hmrc.apiplatformmicroservice.connectors

import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.MockitoSugar
import org.scalatest.{Matchers, OptionValues, WordSpec}
import org.scalatestplus.play.WsScalaTestClient
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.apiplatformmicroservice.models.UnusedApplicationToBeDeletedNotification
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EmailConnectorSpec
  extends WordSpec
    with Matchers
    with OptionValues
    with WsScalaTestClient
    with MockitoSugar
    with ArgumentMatchersSugar
    with DefaultAwaitTimeout
    with FutureAwaits {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val baseUrl = s"http://example.com"

  trait Setup {
    val mockHttpClient = mock[HttpClient]
    val config = EmailConfig(baseUrl)
    val connector = new EmailConnector(mockHttpClient, config)

    val expectedUrl = s"${config.baseUrl}/hmrc/email"

    def emailServiceWillReturn(result: Future[HttpResponse]) = {
      when(mockHttpClient.POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), *, *)(*, *, *, *)).thenReturn(result)
    }

    def verifyEmailServiceCalled(request: SendEmailRequest) = {
      verify(mockHttpClient).POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), eqTo(request), *)(*, *, *, *)
    }
  }

  "emailConnector" should {
    "send unused application to be deleted email" in new Setup {

      val expectedTemplateId = "apiApplicationToBeDeletedNotification"

      val adminEmail = "admin1@example.com"
      val applicationName = "Test Application"
      val userFirstName = "Fred"
      val userLastName = "Bloggs"
      val environmentName = "External Test"
      val timeSinceLastUse = "335 days"
      val timeBeforeDeletion = "365 days"
      val dateOfScheduledDeletion = "20 June 2020"

      val notification =
        UnusedApplicationToBeDeletedNotification(
          adminEmail, userFirstName, userLastName, applicationName, environmentName, timeSinceLastUse, timeBeforeDeletion, dateOfScheduledDeletion)

      emailServiceWillReturn(Future(HttpResponse(OK)))

      val successful = await(connector.sendApplicationToBeDeletedNotification(notification))

      successful should be (true)
      val expectedToEmails = Set(adminEmail)
      val expectedParameters: Map[String, String] = Map(
        "userFirstName" -> userFirstName,
        "userLastName" -> userLastName,
        "applicationName" -> applicationName,
        "environmentName" -> environmentName,
        "timeSinceLastUse" -> timeSinceLastUse,
        "timeBeforeDeletion" -> timeBeforeDeletion,
        "dateOfScheduledDeletion" -> dateOfScheduledDeletion
      )
      verifyEmailServiceCalled(SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters))
    }

    "return false if email service returns 404" in new Setup {
      emailServiceWillReturn(Future(HttpResponse(NOT_FOUND)))

      val successful = await(connector.sendApplicationToBeDeletedNotification(UnusedApplicationToBeDeletedNotification(
        "adminEmail", "userFirstName", "userLastName", "applicationName", "environmentName", "timeSinceLastUse", "timeBeforeDeletion", "dateOfScheduledDeletion")))

      successful should be (false)
    }

    "return false for any other failure" in new Setup {
      emailServiceWillReturn(Future(HttpResponse(INTERNAL_SERVER_ERROR, Some(Json.obj("message" -> "error")))))

      val successful = await(connector.sendApplicationToBeDeletedNotification(UnusedApplicationToBeDeletedNotification(
        "adminEmail", "userFirstName", "userLastName", "applicationName", "environmentName", "timeSinceLastUse", "timeBeforeDeletion", "dateOfScheduledDeletion")))

      successful should be (false)
    }
  }
}
