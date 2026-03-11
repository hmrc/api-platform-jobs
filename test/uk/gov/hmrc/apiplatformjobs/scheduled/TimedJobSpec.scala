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
import java.time.{LocalDate, LocalTime}
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.config.ConfigFactory

import play.api.Configuration

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class TimedJobSpec extends AsyncHmrcSpec with FixedClock {

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

      new TimedJob(jobName, configuration, FixedClock.clock, mockLockRepository) {
        override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] =
          Future.successful(RunningOfJobSuccessful)
      }
    }
  }

  "initialStartTime()" should {
    val today: LocalDate    = now.toLocalDate()
    val tomorrow: LocalDate = today.plusDays(1)
    val tenAM: LocalTime    = LocalTime.of(10, 0)
    val elevenPM: LocalTime = LocalTime.of(11, 0)
    val hourLongInterval    = 60L
    val fourHourInterval    = hourLongInterval * 4

    "calculate where earliest start time before and within one interval of configured start time" in {
      TimedJob.initialStartTime(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(9, 33, 15).atDate(today)) shouldBe today.atTime(tenAM)
    }
    "calculate where earliest start time before and within two intervals of configured start time" in {
      TimedJob.initialStartTime(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(7, 30).atDate(today)) shouldBe today.atTime(LocalTime.of(8, 0))
    }
    "calculate where earliest start time after but within one intervals of configured start time" in {
      TimedJob.initialStartTime(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(10, 30).atDate(today)) shouldBe today.atTime(LocalTime.of(11, 0))
    }
    "calculate where earliest start time after but within two intervals of configured start time" in {
      TimedJob.initialStartTime(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(11, 50).atDate(today)) shouldBe today.atTime(LocalTime.of(12, 0))
    }

    "calculate where earliest start time after but within one interval of configured start time but rolls to next day" in {
      TimedJob.initialStartTime(configuredStartTime = elevenPM, fourHourInterval, LocalTime.of(23, 30).atDate(today)) shouldBe tomorrow.atTime(3, 0)
    }
  }

  "calculateInitialDelay()" should {
    import scala.concurrent.duration._

    val today: LocalDate    = now.toLocalDate()
    val tenAM: LocalTime    = LocalTime.of(10, 0)
    val elevenPM: LocalTime = LocalTime.of(11, 0)
    val hourLongInterval    = 60L
    val fourHourInterval    = hourLongInterval * 4

    "calculate where earliest start time before and within one interval of configured start time" in {
      TimedJob.calculateInitialDelay(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(9, 33, 15).atDate(today)) shouldBe 26.minutes.plus(45.seconds)
    }
    "calculate where earliest start time before and within two intervals of configured start time" in {
      TimedJob.calculateInitialDelay(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(7, 30).atDate(today)) shouldBe 30.minutes
    }
    "calculate where earliest start time after but within one intervals of configured start time" in {
      TimedJob.calculateInitialDelay(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(10, 45).atDate(today)) shouldBe 15.minutes
    }
    "calculate where earliest start time after but within two intervals of configured start time" in {
      TimedJob.calculateInitialDelay(configuredStartTime = tenAM, hourLongInterval, LocalTime.of(11, 50).atDate(today)) shouldBe 10.minutes
    }

    "calculate where earliest start time after but within one interval of configured start time but rolls to next day" in {
      TimedJob.calculateInitialDelay(configuredStartTime = elevenPM, fourHourInterval, LocalTime.of(23, 30).atDate(today)) shouldBe 3.hours.plus(30.minutes)
    }
  }

  "TestJob" should {
    "correctly calculate time until first run if time is later today" in new TestJobSetup {
      val futureTime    = now.plusHours(1).withSecond(0).withNano(0)
      val expectedDelay = futureTime.toInstant(utc).toEpochMilli - instant.toEpochMilli

      val jobToTest = testJob(futureTime.format(DateTimeFormatter.ofPattern("HH:mm")), "1d")

      jobToTest.initialDelay.toMillis shouldBe expectedDelay
    }

    "correctly calculate time until first run if time is earlier today" in new TestJobSetup {
      val pastTime      = now.minusHours(1).withSecond(0).withNano(0)
      val expectedDelay = pastTime.plusDays(1).toInstant(utc).toEpochMilli - instant.toEpochMilli

      val jobToTest = testJob(pastTime.format(DateTimeFormatter.ofPattern("HH:mm")), "1d")

      jobToTest.initialDelay.toMillis shouldBe expectedDelay
    }
  }
}
