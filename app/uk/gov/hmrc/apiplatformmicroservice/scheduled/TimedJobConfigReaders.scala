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

package uk.gov.hmrc.apiplatformmicroservice.scheduled

import com.typesafe.config.{Config, ConfigException}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.joda.time.LocalTime

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait TimedJobConfigReaders {

  implicit def localTimeReader: ValueReader[LocalTime] = (config: Config, path: String) => {
    (Try {
      LocalTime.parse(config.getString(path))
    } recover {
      case ex => throw new ConfigException.BadValue(path, config.getString(path), ex)
    }).get
  }

  implicit def timedJobConfigReader: ValueReader[TimedJobConfig] = ValueReader.relative[TimedJobConfig] {
    config =>
      val parsedStartTime: Option[StartTime] = config.as[Option[LocalTime]]("startTime").map(new StartTime(_))
      val parsedExecutionInterval = new ExecutionInterval(config.as[FiniteDuration]("executionInterval"))
      val parsedEnabled: Boolean = config.as[Option[Boolean]]("enabled").getOrElse(false)

      TimedJobConfig(parsedStartTime, parsedExecutionInterval, parsedEnabled)
  }

  implicit def updateUnusedApplicationsJobConfigReader: ValueReader[UpdateUnusedApplicationRecordsJobConfig] =
    ValueReader.relative[UpdateUnusedApplicationRecordsJobConfig] {
      config =>
        val notifyDeletionPendingInAdvance = config.as[FiniteDuration]("notifyDeletionPendingInAdvance")
        val externalEnvironmentName = config.as[String]("externalEnvironmentName")

        UpdateUnusedApplicationRecordsJobConfig(notifyDeletionPendingInAdvance, externalEnvironmentName)
    }
}

object TimedJobConfigReaders extends TimedJobConfigReaders
