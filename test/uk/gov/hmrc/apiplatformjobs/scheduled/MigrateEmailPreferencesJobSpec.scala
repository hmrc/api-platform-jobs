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

package uk.gov.hmrc.apiplatformjobs.scheduled

import java.util.concurrent.TimeUnit.{HOURS, SECONDS}

import akka.stream.Materializer
import org.joda.time.Duration
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.connectors.{ApiPlatformMicroserviceConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.EmailTopic.{BUSINESS_AND_POLICY, EVENT_INVITES, RELEASE_SCHEDULES, TECHNICAL}
import uk.gov.hmrc.apiplatformjobs.models.{APIDefinition, EmailPreferences, EmailTopic, TaxRegimeInterests}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future.{failed, successful}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class MigrateEmailPreferencesJobSpec extends UnitSpec with MockitoSugar with MongoSpecSupport with GuiceOneAppPerSuite {

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .disable[com.kenshoo.play.metrics.PlayModule]
    .configure("metrics.enabled" -> false).build()
  implicit lazy val materializer: Materializer = app.materializer

  trait Setup {
    implicit val hc = HeaderCarrier()
    val lockKeeperSuccess: () => Boolean = () => true
    private val reactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    val mockLockKeeper: MigrateEmailPreferencesJobLockKeeper = new MigrateEmailPreferencesJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = "testLock"
      override def repo: LockRepository = mock[LockRepository]
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => successful(Some(value)))
        else successful(None)
    }

    val migrateEmailPreferencesJobConfig: MigrateEmailPreferencesJobConfig = MigrateEmailPreferencesJobConfig(
      FiniteDuration(60, SECONDS), FiniteDuration(24, HOURS), enabled = true)
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector = mock[ThirdPartyDeveloperConnector]
    val mockApiPlatformMicroserviceConnector: ApiPlatformMicroserviceConnector = mock[ApiPlatformMicroserviceConnector]
    val underTest = new MigrateEmailPreferencesJob(
      mockLockKeeper,
      migrateEmailPreferencesJobConfig,
      mockThirdPartyDeveloperConnector,
      mockApiPlatformMicroserviceConnector
    )
    when(mockThirdPartyDeveloperConnector.updateEmailPreferences(any(), any())(any())).thenReturn(successful(200))
  }

  "MigrateEmailPreferencesJob" should {
    import scala.concurrent.ExecutionContext.Implicits.global

    "migrate email preferences for all developers" in new Setup {
      val developers = Seq("joe.bloggs@example.com", "john.doe@example.com")
      when(mockThirdPartyDeveloperConnector.fetchAllDevelopers(any())).thenReturn(successful(developers))
      when(mockApiPlatformMicroserviceConnector.fetchApiDefinitionsForCollaborator(meq("joe.bloggs@example.com"))(any()))
        .thenReturn(successful(Seq(APIDefinition("def 1", Seq("VAT")))))
      when(mockApiPlatformMicroserviceConnector.fetchApiDefinitionsForCollaborator(meq("john.doe@example.com"))(any()))
        .thenReturn(successful(Seq(APIDefinition("def 1", Seq("VAT")))))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, times(1)).updateEmailPreferences(meq("joe.bloggs@example.com"), any())(any())
      verify(mockThirdPartyDeveloperConnector, times(1)).updateEmailPreferences(meq("john.doe@example.com"), any())(any())
      result.message shouldBe "MigrateEmailPreferencesJob Job ran successfully."
    }

    "combine APIs from the same category" in new Setup {
      val developer = "joe.bloggs@example.com"
      when(mockThirdPartyDeveloperConnector.fetchAllDevelopers(any())).thenReturn(successful(Seq(developer)))
      when(mockApiPlatformMicroserviceConnector.fetchApiDefinitionsForCollaborator(meq(developer))(any()))
        .thenReturn(successful(Seq(APIDefinition("def 1", Seq("VAT", "AGENTS")), APIDefinition("def 2", Seq("AGENTS", "OTHER")))))

      val result: underTest.Result = await(underTest.execute)

      val expectedInterests = Seq(TaxRegimeInterests("AGENTS", Set("def 1", "def 2")), TaxRegimeInterests("OTHER", Set("def 2")), TaxRegimeInterests("VAT", Set("def 1")))
      val expectedTopics: Set[EmailTopic] = Set(BUSINESS_AND_POLICY, TECHNICAL, RELEASE_SCHEDULES, EVENT_INVITES)
      val expectedEmailPreferences = EmailPreferences(expectedInterests, expectedTopics)
      verify(mockThirdPartyDeveloperConnector, times(1)).updateEmailPreferences(meq("joe.bloggs@example.com"), meq(expectedEmailPreferences))(any())
      result.message shouldBe "MigrateEmailPreferencesJob Job ran successfully."
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).fetchAllDevelopers(any())
      result.message shouldBe "MigrateEmailPreferencesJob did not run because repository was locked by another instance of the scheduler."
    }

    "handle error when something fails" in new Setup {
      when(mockThirdPartyDeveloperConnector.fetchAllDevelopers(any())).thenReturn(failed(new RuntimeException("Failed")))

      val result: underTest.Result = await(underTest.execute)

      verify(mockThirdPartyDeveloperConnector, never).updateEmailPreferences(any(), any())(any())
      result.message shouldBe "The execution of scheduled job MigrateEmailPreferencesJob failed with error 'Failed'. " +
        "The next execution of the job will do retry."
    }
  }
}
