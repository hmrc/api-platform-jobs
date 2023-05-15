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

import java.time.Clock

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

import play.api.Configuration
import uk.gov.hmrc.mongo.lock.LockRepository

import uk.gov.hmrc.apiplatformjobs.models.Environment

abstract class UnusedApplicationsJob(jobName: String, environment: Environment, configuration: Configuration, clock: Clock, lockRepository: LockRepository)
    extends TimedJob(s"$jobName.$environment", configuration, clock, lockRepository)
    with UnusedApplicationsTimings {

  override val unusedApplicationsConfiguration: UnusedApplicationsConfiguration =
    configuration.underlying.as[UnusedApplicationsConfiguration]("UnusedApplications")

  def jobSpecificConfiguration[C]()(implicit reader: ValueReader[C]): C = configuration.underlying.as[C](name)
}
