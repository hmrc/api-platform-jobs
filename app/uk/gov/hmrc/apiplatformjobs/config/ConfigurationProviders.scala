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

import java.util.concurrent.TimeUnit.{HOURS, SECONDS}
import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.duration.{Duration, FiniteDuration}

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector.ThirdPartyApplicationConnectorConfig
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.ThirdPartyDeveloperConnectorConfig
import uk.gov.hmrc.apiplatformjobs.connectors.{ApiPlatformMicroserviceConnectorConfig, EmailConfig}
import uk.gov.hmrc.apiplatformjobs.scheduled.{DeleteUnregisteredDevelopersJobConfig, DeleteUnverifiedDevelopersJobConfig}

class ConfigurationModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[ApiPlatformMicroserviceConnectorConfig].toProvider[ApiPlatformMicroserviceConnectorConfigProvider],
      bind[ThirdPartyDeveloperConnectorConfig].toProvider[ThirdPartyDeveloperConnectorConfigProvider],
      bind[ThirdPartyApplicationConnectorConfig].toProvider[ThirdPartyApplicationConnectorConfigProvider],
      bind[DeleteUnverifiedDevelopersJobConfig].toProvider[DeleteUnverifiedDevelopersJobConfigProvider],
      bind[DeleteUnregisteredDevelopersJobConfig].toProvider[DeleteUnregisteredDevelopersJobConfigProvider],
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
class ThirdPartyApplicationConnectorConfigProvider @Inject() (val sc: ServicesConfig) extends Provider[ThirdPartyApplicationConnectorConfig] {

  private def serviceUrl(key: String)(serviceName: String): String = {
    if (useProxy(serviceName)) s"${sc.baseUrl(serviceName)}/${sc.getConfString(s"$serviceName.context", key)}"
    else sc.baseUrl(serviceName)
  }

  private def useProxy(serviceName: String) = sc.getConfBool(s"$serviceName.use-proxy", false)

  private def bearerToken(serviceName: String) = sc.getConfString(s"$serviceName.bearer-token", "")

  private def apiKey(serviceName: String) = sc.getConfString(s"$serviceName.api-key", "")

  private def authorisationKey(serviceName: String) = sc.getConfString(s"$serviceName.authorisationKey", "")

  override def get(): ThirdPartyApplicationConnectorConfig = {
    ThirdPartyApplicationConnectorConfig(
      serviceUrl("third-party-application")("third-party-application-sandbox"),
      useProxy("third-party-application-sandbox"),
      bearerToken("third-party-application-sandbox"),
      apiKey("third-party-application-sandbox"),
      authorisationKey("third-party-application-sandbox"),
      serviceUrl("third-party-application")("third-party-application-production"),
      useProxy("third-party-application-production"),
      bearerToken("third-party-application-production"),
      apiKey("third-party-application-production"),
      authorisationKey("third-party-application-production")
    )
  }
}

@Singleton
class DeleteUnverifiedDevelopersJobConfigProvider @Inject() (configuration: Configuration) extends Provider[DeleteUnverifiedDevelopersJobConfig] {

  override def get(): DeleteUnverifiedDevelopersJobConfig = {
    val initialDelay = configuration
      .getOptional[String]("deleteUnverifiedDevelopersJob.initialDelay")
      .map(Duration.create(_).asInstanceOf[FiniteDuration])
      .getOrElse(FiniteDuration(60, SECONDS))
    val interval     = configuration
      .getOptional[String]("deleteUnverifiedDevelopersJob.interval")
      .map(Duration.create(_).asInstanceOf[FiniteDuration])
      .getOrElse(FiniteDuration(1, HOURS))
    val enabled      = configuration.getOptional[Boolean]("deleteUnverifiedDevelopersJob.enabled").getOrElse(false)
    val limit        = configuration.getOptional[Int]("deleteUnverifiedDevelopersJob.limit").getOrElse(10)
    DeleteUnverifiedDevelopersJobConfig(initialDelay, interval, enabled, limit)
  }
}

@Singleton
class DeleteUnregisteredDevelopersJobConfigProvider @Inject() (configuration: Configuration) extends Provider[DeleteUnregisteredDevelopersJobConfig] {

  override def get(): DeleteUnregisteredDevelopersJobConfig = {
    val initialDelay = configuration
      .getOptional[String]("deleteUnregisteredDevelopersJob.initialDelay")
      .map(Duration.create(_).asInstanceOf[FiniteDuration])
      .getOrElse(FiniteDuration(120, SECONDS))
    val interval     = configuration
      .getOptional[String]("deleteUnregisteredDevelopersJob.interval")
      .map(Duration.create(_).asInstanceOf[FiniteDuration])
      .getOrElse(FiniteDuration(1, HOURS))
    val enabled      = configuration.getOptional[Boolean]("deleteUnregisteredDevelopersJob.enabled").getOrElse(false)
    val limit        = configuration.getOptional[Int]("deleteUnregisteredDevelopersJob.limit").getOrElse(10)
    DeleteUnregisteredDevelopersJobConfig(initialDelay, interval, enabled, limit)
  }
}

@Singleton
class EmailConfigProvider @Inject() (val sc: ServicesConfig) extends Provider[EmailConfig] {
  override def get() = EmailConfig(sc.baseUrl("email"))
}

object ConfigHelper {
  def getConfig[T](key: String, f: String => Option[T]): T = {
    f(key).getOrElse(throw new RuntimeException(s"[$key] is not configured!"))
  }
}
