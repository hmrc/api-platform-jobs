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

package uk.gov.hmrc.apiplatformjobs.config

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.duration.FiniteDuration

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.ThirdPartyDeveloperConnectorConfig
import uk.gov.hmrc.apiplatformjobs.connectors.{ApiPlatformMicroserviceConnectorConfig, EmailConfig, ThirdPartyOrchestratorConnector}
import uk.gov.hmrc.apiplatformjobs.scheduled.{DeleteUnregisteredDevelopersJobConfig, DeleteUnverifiedDevelopersJobConfig}

class ConfigurationModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[ApiPlatformMicroserviceConnectorConfig].toProvider[ApiPlatformMicroserviceConnectorConfigProvider],
      bind[ThirdPartyDeveloperConnectorConfig].toProvider[ThirdPartyDeveloperConnectorConfigProvider],
      bind[DeleteUnverifiedDevelopersJobConfig].toProvider[DeleteUnverifiedDevelopersJobConfigProvider],
      bind[DeleteUnregisteredDevelopersJobConfig].toProvider[DeleteUnregisteredDevelopersJobConfigProvider],
      bind[ThirdPartyOrchestratorConnector.Config].toProvider[ThirdPartyOrchestratorConnectorConfigProvider],
      bind[EmailConfig].toProvider[EmailConfigProvider]
    )
  }
}

@Singleton
class ApiPlatformMicroserviceConnectorConfigProvider @Inject() (val sc: ServicesConfig) extends Provider[ApiPlatformMicroserviceConnectorConfig] {

  override def get(): ApiPlatformMicroserviceConnectorConfig = {
    ApiPlatformMicroserviceConnectorConfig(sc.baseUrl("api-platform-microservice"))
  }
}

@Singleton
class ThirdPartyDeveloperConnectorConfigProvider @Inject() (val sc: ServicesConfig) extends Provider[ThirdPartyDeveloperConnectorConfig] {

  override def get(): ThirdPartyDeveloperConnectorConfig = {
    ThirdPartyDeveloperConnectorConfig(sc.baseUrl("third-party-developer"))
  }
}

@Singleton
class DeleteUnverifiedDevelopersJobConfigProvider @Inject() (configuration: Configuration) extends Provider[DeleteUnverifiedDevelopersJobConfig] {
  import scala.jdk.DurationConverters._

  // scalastyle:off magic.number
  override def get(): DeleteUnverifiedDevelopersJobConfig = {
    val enabled: Boolean             = configuration.underlying.getBoolean("deleteUnverifiedDevelopersJob.enabled")
    val initialDelay: FiniteDuration = configuration.underlying.getDuration("deleteUnverifiedDevelopersJob.initialDelay").toScala
    val interval: FiniteDuration     = configuration.underlying.getDuration("deleteUnverifiedDevelopersJob.interval").toScala
    val limit: Int                   = configuration.underlying.getInt("deleteUnverifiedDevelopersJob.limit")
    DeleteUnverifiedDevelopersJobConfig(initialDelay, interval, enabled, limit)
  }
}

@Singleton
class DeleteUnregisteredDevelopersJobConfigProvider @Inject() (configuration: Configuration) extends Provider[DeleteUnregisteredDevelopersJobConfig] {
  import scala.jdk.DurationConverters._

  override def get(): DeleteUnregisteredDevelopersJobConfig = {
    val enabled: Boolean             = configuration.underlying.getBoolean("deleteUnregisteredDevelopersJob.enabled")
    val initialDelay: FiniteDuration = configuration.underlying.getDuration("deleteUnregisteredDevelopersJob.initialDelay").toScala
    val interval: FiniteDuration     = configuration.underlying.getDuration("deleteUnregisteredDevelopersJob.interval").toScala
    val limit: Int                   = configuration.underlying.getInt("deleteUnregisteredDevelopersJob.limit")
    DeleteUnregisteredDevelopersJobConfig(initialDelay, interval, enabled, limit)
  }
}

@Singleton
class EmailConfigProvider @Inject() (val sc: ServicesConfig) extends Provider[EmailConfig] {
  override def get() = EmailConfig(sc.baseUrl("email"))
}

@Singleton
class ThirdPartyOrchestratorConnectorConfigProvider @Inject() (config: ServicesConfig) extends Provider[ThirdPartyOrchestratorConnector.Config] {

  override def get(): ThirdPartyOrchestratorConnector.Config =
    ThirdPartyOrchestratorConnector.Config(
      serviceBaseUrl = config.baseUrl("third-party-orchestrator")
    )
}

object ConfigHelper {

  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }
}
