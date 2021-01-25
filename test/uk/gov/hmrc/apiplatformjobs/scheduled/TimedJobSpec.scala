/*
 * Copyright 2021 HM Revenue & Customs
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

import com.typesafe.config.ConfigFactory
import org.joda.time.{DateTime, DateTimeUtils, DateTimeZone, LocalTime}
import play.api.{Configuration, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class TimedJobSpec extends AsyncHmrcSpec with MongoSpecSupport {

  trait TestJobSetup {
    val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }
    val mockLogger = mock[LoggerLike]

    def testJob(startTime: String, executionInterval: String, enabled: Boolean = true) = {
      val jobName = "TestJob"
      val configuration =
        new Configuration(
          ConfigFactory.parseString(s"""
          |$jobName {
          |    startTime = "$startTime"
          |    executionInterval = $executionInterval
          |    enabled = $enabled
          |  }""".stripMargin))

      new TimedJob(jobName, configuration, reactiveMongoComponent, mockLogger) {
        override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] =
          Future.successful(RunningOfJobSuccessful)
      }
    }
  }

  trait FixedTime {
    val BaselineTime = DateTime.now.getMillis
    DateTimeUtils.setCurrentMillisFixed(BaselineTime)
  }

  "calculateInitialDelay()" should {
    "correctly calculate time until first run if time is later today" in new TestJobSetup with FixedTime {
      val futureTime = LocalTime.now(DateTimeZone.UTC).plusHours(1).withSecondOfMinute(0).withMillisOfSecond(0)
      val expectedDelay = futureTime.toDateTimeToday(DateTimeZone.UTC).getMillis - DateTime.now(DateTimeZone.UTC).getMillis

      val jobToTest = testJob(futureTime.toString("HH:mm"), "1d")

      jobToTest.initialDelay.toMillis shouldBe (expectedDelay)
    }

    "correctly calculate time until first run if time is earlier today" in new TestJobSetup with FixedTime {
      val pastTime = LocalTime.now(DateTimeZone.UTC).minusHours(1).withSecondOfMinute(0).withMillisOfSecond(0)
      val expectedDelay = pastTime.toDateTimeToday(DateTimeZone.UTC).plusDays(1).getMillis - DateTime.now(DateTimeZone.UTC).getMillis

      val jobToTest = testJob(pastTime.toString("HH:mm"), "1d")

      jobToTest.initialDelay.toMillis shouldBe (expectedDelay)
    }
  }
}
