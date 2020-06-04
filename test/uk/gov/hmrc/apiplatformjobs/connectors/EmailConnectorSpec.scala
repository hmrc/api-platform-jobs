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

import java.util.UUID

import org.joda.time.{DateTime, LocalDate}
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.MockitoSugar
import org.scalatest.{Matchers, OptionValues, WordSpec}
import org.scalatestplus.play.WsScalaTestClient
import play.api.http.Status._
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import uk.gov.hmrc.apiplatformjobs.models.{Administrator, Environment, UnusedApplication}
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

    def verifyEmailServiceCalled(request: SendEmailRequest) = {
      verify(mockHttpClient).POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), eqTo(request), *)(*, *, *, *)
    }
  }

  trait ApplicationToBeDeletedNotificationDetails {
    val expectedTemplateId = "apiApplicationToBeDeletedNotification"

    val adminEmail = "admin1@example.com"
    val applicationName = "Test Application"
    val userFirstName = "Fred"
    val userLastName = "Bloggs"
    val environmentName = "SANDBOX"
    val timeSinceLastUse = "335 days"

    val lastAccessDate = DateTime.now.minusDays(335)
    val scheduledDeletionDate = LocalDate.now.plusDays(30)

    val expectedDeletionDateString = scheduledDeletionDate.toString("dd MMMM yyyy")

    val unusedApplication =
      UnusedApplication(
        UUID.randomUUID,
        applicationName,
        Seq(Administrator(adminEmail, userFirstName, userLastName)),
        Environment.SANDBOX,
        lastAccessDate,
        Seq.empty,
        scheduledDeletionDate)

    val unusedApplicationWithMultipleAdmins =
      UnusedApplication(
        UUID.randomUUID,
        applicationName,
        Seq(Administrator(adminEmail, userFirstName, userLastName), Administrator(adminEmail, userFirstName, userLastName)),
        Environment.SANDBOX,
        lastAccessDate,
        Seq.empty,
        scheduledDeletionDate)
  }

  "emailConnector" should {
    "send unused application to be deleted email" in new Setup with ApplicationToBeDeletedNotificationDetails {

      when(mockHttpClient.POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), *, *)(*, *, *, *)).thenReturn(Future(HttpResponse(OK)))

      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplication))

      successful should be (true)
      val expectedToEmails = Set(adminEmail)
      val expectedParameters: Map[String, String] = Map(
        "userFirstName" -> userFirstName,
        "userLastName" -> userLastName,
        "applicationName" -> applicationName,
        "environmentName" -> environmentName,
        "timeSinceLastUse" -> timeSinceLastUse,
        "dateOfScheduledDeletion" -> expectedDeletionDateString
      )
      verifyEmailServiceCalled(SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters))
    }

    "return true if any notification succeeds" in new Setup with ApplicationToBeDeletedNotificationDetails {
      when(mockHttpClient.POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), *, *)(*, *, *, *))
        .thenReturn(Future(HttpResponse(OK)))
        .andThen(Future(HttpResponse(NOT_FOUND)))

      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplicationWithMultipleAdmins))

      successful should be (true)
    }

    "return false if all notifications fail" in new Setup with ApplicationToBeDeletedNotificationDetails {
      when(mockHttpClient.POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), *, *)(*, *, *, *)).thenReturn(Future(HttpResponse(NOT_FOUND)))

      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplicationWithMultipleAdmins))

      successful should be (false)
    }
  }
}
