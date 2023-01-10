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
import uk.gov.hmrc.apiplatformjobs.models.{Administrator, Environment, UnusedApplication}
import uk.gov.hmrc.apiplatformjobs.util.{AsyncHmrcSpec, UrlEncoding}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class EmailConnectorSpec extends AsyncHmrcSpec with RepsonseUtils with GuiceOneAppPerSuite with WiremockSugar with UrlEncoding {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  implicit val hc: HeaderCarrier = HeaderCarrier()

  trait Setup {
    val http      = app.injector.instanceOf[HttpClient]
    val config    = EmailConfig(wireMockUrl)
    val connector = new EmailConnector(http, config)
  }

  trait ApplicationToBeDeletedNotificationDetails {
    val expectedTemplateId = "apiApplicationToBeDeletedNotification"

    val adminEmail       = "admin1@example.com"
    val applicationName  = "Test Application"
    val userFirstName    = "Fred"
    val userLastName     = "Bloggs"
    val environmentName  = "Sandbox"
    val timeSinceLastUse = "335 days"

    val lastAccessDate             = LocalDateTime.now.minusDays(335)
    val scheduledDeletionDate      = LocalDateTime.now.plusDays(30).toLocalDate
    val dateTimeFormatter          = DateTimeFormatter.ofPattern("dd MMMM yyyy")
    val expectedDeletionDateString = scheduledDeletionDate.format(dateTimeFormatter)

    val unusedApplication =
      UnusedApplication(
        UUID.randomUUID,
        applicationName,
        Seq(Administrator(adminEmail, userFirstName, userLastName)),
        Environment.SANDBOX,
        lastAccessDate,
        Seq.empty,
        scheduledDeletionDate
      )

    val unusedApplicationWithMultipleAdmins =
      UnusedApplication(
        UUID.randomUUID,
        applicationName,
        Seq(Administrator(adminEmail, userFirstName, userLastName), Administrator(adminEmail, userFirstName, userLastName)),
        Environment.SANDBOX,
        lastAccessDate,
        Seq.empty,
        scheduledDeletionDate
      )
  }

  "emailConnector" should {
    "send unused application to be deleted email" in new Setup with ApplicationToBeDeletedNotificationDetails {
      val expectedToEmails                        = Set(adminEmail)
      val expectedParameters: Map[String, String] = Map(
        "userFirstName"           -> userFirstName,
        "userLastName"            -> userLastName,
        "applicationName"         -> applicationName,
        "environmentName"         -> environmentName,
        "timeSinceLastUse"        -> timeSinceLastUse,
        "dateOfScheduledDeletion" -> expectedDeletionDateString
      )

      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .withJsonRequestBody(SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )

      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplication, environmentName))

      successful should be(true)
    }

    "return true if any notification succeeds" in new Setup with ApplicationToBeDeletedNotificationDetails {
      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )
      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplicationWithMultipleAdmins, environmentName))

      successful should be(true)
    }

    "return false if all notifications fail" in new Setup with ApplicationToBeDeletedNotificationDetails {
      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )
      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplicationWithMultipleAdmins, environmentName))

      successful should be(false)
    }
  }
}
