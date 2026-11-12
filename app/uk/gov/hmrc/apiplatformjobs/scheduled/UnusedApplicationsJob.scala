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

import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation

import play.api.Configuration
import uk.gov.hmrc.mongo.lock.MongoLockRepository

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Environment
import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow

abstract class UnusedApplicationsJob(jobName: String, environment: Environment, configuration: Configuration, override val clock: Clock, mongoLockRepository: MongoLockRepository)
    extends TimedJob(s"$jobName.${environment.displayText.toUpperCase}", configuration, clock, mongoLockRepository)
    with UnusedApplicationsTimings with ClockNow {

  import pureconfig.generic.ProductHint
  import pureconfig.*
  import pureconfig.generic.semiauto.deriveReader
  import pureconfig.generic.*
  import scala.deriving.Mirror

  extension (c: ConfigReader.type)
    inline def derived[A: Mirror.Of, ProductHint, CoproductHint]: ConfigReader[A] = deriveReader[A]

  given ProductHint[UnusedApplicationsConfiguration] =
    ProductHint.apply[UnusedApplicationsConfiguration](fieldMapping = ConfigFieldMapping(_.toUpperCase))

  given productHintUnusedApplicationsEnvironmentConfiguration: ProductHint[UnusedApplicationsEnvironmentConfiguration] =
    ProductHint.apply[UnusedApplicationsEnvironmentConfiguration](fieldMapping = ConfigFieldMapping(CamelCase, CamelCase))

  given ConfigReader[UnusedApplicationsConfiguration] = deriveReader

  override val unusedApplicationsConfiguration: UnusedApplicationsConfiguration = {
    ConfigSource.fromConfig(configuration.underlying).at("UnusedApplications").loadOrThrow[UnusedApplicationsConfiguration]
  }
}
