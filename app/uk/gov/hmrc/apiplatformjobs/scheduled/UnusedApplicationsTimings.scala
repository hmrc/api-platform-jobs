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

import scala.concurrent.duration.FiniteDuration

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Environment

trait UnusedApplicationsTimings {
  val unusedApplicationsConfiguration: UnusedApplicationsConfiguration

  /** The amount of time an application be unused for in a given environment before it is deleted */
  def deleteUnusedApplicationsAfter(environment: Environment): FiniteDuration =
    environment match {
      case Environment.SANDBOX    => unusedApplicationsConfiguration.sandbox.deleteUnusedApplicationsAfter
      case Environment.PRODUCTION => unusedApplicationsConfiguration.production.deleteUnusedApplicationsAfter
    }

  /** How far in advance of deletion notifications are sent out for the environment */
  def sendNotificationsInAdvance(environment: Environment): Set[FiniteDuration] =
    environment match {
      case Environment.SANDBOX    => unusedApplicationsConfiguration.sandbox.sendNotificationsInAdvance
      case Environment.PRODUCTION => unusedApplicationsConfiguration.production.sendNotificationsInAdvance
    }

  /** How far in advance the first notification needs to be sent (i.e. what is the largest sendNotificationsInAdvance value for the environment) */
  def firstNotificationInAdvance(environment: Environment): FiniteDuration =
    environment match {
      case Environment.SANDBOX    => unusedApplicationsConfiguration.sandbox.sendNotificationsInAdvance.max
      case Environment.PRODUCTION => unusedApplicationsConfiguration.production.sendNotificationsInAdvance.max
    }

  def environmentName(environment: Environment): String =
    environment match {
      case Environment.SANDBOX    => unusedApplicationsConfiguration.sandbox.environmentName
      case Environment.PRODUCTION => unusedApplicationsConfiguration.production.environmentName
    }

}

case class UnusedApplicationsConfiguration(sandbox: UnusedApplicationsEnvironmentConfiguration, production: UnusedApplicationsEnvironmentConfiguration)

case class UnusedApplicationsEnvironmentConfiguration(
    deleteUnusedApplicationsAfter: FiniteDuration,
    sendNotificationsInAdvance: Set[FiniteDuration],
    authorisationKey: String,
    environmentName: String
  )
