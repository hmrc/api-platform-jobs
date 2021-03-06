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

package uk.gov.hmrc.apiplatformjobs.config

import play.api.Configuration
import com.google.inject.{Singleton, Provider, Inject}
import uk.gov.hmrc.play.bootstrap.config.{RunMode, ServicesConfig}
import uk.gov.hmrc.apiplatformjobs.scheduled.JobConfig

@Singleton
class MigrateCollaboratorsIdJobConfigProvider @Inject()(configuration: Configuration, runMode: RunMode)
  extends ServicesConfig(configuration, runMode) with Provider[JobConfig] {

  override def get(): JobConfig = {
    val config = configuration.underlying.getConfig("migrateCollaboratorsIdJob")
    JobConfig.fromConfig(config)
  }
}
