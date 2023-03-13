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

package uk.gov.hmrc.apiplatformjobs.scheduled

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime, LocalTime}
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.config.ConfigFactory

import play.api.{Configuration}

import uk.gov.hmrc.apiplatformjobs.util.AsyncHmrcSpec

class TimedJobSpec extends AsyncHmrcSpec {

  trait TestJobSetup extends BaseSetup {
    def testJob(startTime: String, executionInterval: String, enabled: Boolean = true) = {
      val jobName       = "TestJob"
      val configuration =
        new Configuration(ConfigFactory.parseString(s"""
                                                       |$jobName {
                                                       |    startTime = "$startTime"
                                                       |    executionInterval = $executionInterval
                                                       |    enabled = $enabled
                                                       |  }""".stripMargin))

      new TimedJob(jobName, configuration, fixedClock, mockLockRepository) {
        override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] =
          Future.successful(RunningOfJobSuccessful)
      }
    }
  }

  "calculateInitialDelay()" should {
    "correctly calculate time until first run if time is later today" in new TestJobSetup {
      val futureTime    = LocalTime.now(fixedClock).plusHours(1).withSecond(0).withNano(0)
      val expectedDelay = LocalDateTime.of(LocalDate.now(fixedClock), futureTime).toInstant(utc).toEpochMilli - LocalDateTime.now(fixedClock).toInstant(utc).toEpochMilli

      val jobToTest = testJob(futureTime.format(DateTimeFormatter.ofPattern("HH:mm")), "1d")

      jobToTest.initialDelay.toMillis shouldBe expectedDelay
    }

    "correctly calculate time until first run if time is earlier today" in new TestJobSetup {
      val pastTime      = LocalTime.now(fixedClock).minusHours(1).withSecond(0).withNano(0)
      val expectedDelay = LocalDateTime.of(LocalDate.now(fixedClock), pastTime).plusDays(1).toInstant(utc).toEpochMilli - LocalDateTime.now(fixedClock).toInstant(utc).toEpochMilli

      val jobToTest = testJob(pastTime.format(DateTimeFormatter.ofPattern("HH:mm")), "1d")

      jobToTest.initialDelay.toMillis shouldBe expectedDelay
    }
  }
}
