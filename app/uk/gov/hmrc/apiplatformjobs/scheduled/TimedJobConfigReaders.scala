/*
 * Copyright 2022 HM Revenue & Customs
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

import com.typesafe.config.{Config, ConfigException}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import uk.gov.hmrc.apiplatformjobs.models.Environment

import java.time.LocalTime
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

  implicit def unusedApplicationsConfigurationReader: ValueReader[UnusedApplicationsConfiguration] =
    ValueReader.relative[UnusedApplicationsConfiguration] { config =>
      def environmentConfiguration(environment: Environment.Value): UnusedApplicationsEnvironmentConfiguration =
        UnusedApplicationsEnvironmentConfiguration(
          config.as[FiniteDuration](s"$environment.deleteUnusedApplicationsAfter"),
          config.as[Set[FiniteDuration]](s"$environment.sendNotificationsInAdvance"),
          config.as[String](s"$environment.environmentName"))

      UnusedApplicationsConfiguration(
        environmentConfiguration(Environment.SANDBOX),
        environmentConfiguration(Environment.PRODUCTION))
    }

}

object TimedJobConfigReaders extends TimedJobConfigReaders
