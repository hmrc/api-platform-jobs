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

package uk.gov.hmrc.apiplatformjobs.scheduled

import uk.gov.hmrc.apiplatformjobs.models.Environment
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment

import scala.concurrent.duration.FiniteDuration


trait UnusedApplicationsTimings {
  val timingsConfiguration: UnusedApplicationsConfiguration

  def deleteUnusedApplicationsAfter(environment: Environment): FiniteDuration =
    environment match {
      case Environment.SANDBOX => timingsConfiguration.sandbox.deleteUnusedApplicationsAfter
      case Environment.PRODUCTION => timingsConfiguration.production.deleteUnusedApplicationsAfter
    }

  def sendNotificationsInAdvance(environment: Environment): Set[FiniteDuration] =
    environment match {
      case Environment.SANDBOX => timingsConfiguration.sandbox.sendNotificationsInAdvance
      case Environment.PRODUCTION => timingsConfiguration.production.sendNotificationsInAdvance
    }

  def firstNotificationInAdvance(environment: Environment): FiniteDuration =
    environment match {
      case Environment.SANDBOX => timingsConfiguration.sandbox.sendNotificationsInAdvance.max
      case Environment.PRODUCTION => timingsConfiguration.production.sendNotificationsInAdvance.max
    }
}

case class UnusedApplicationsConfiguration(sandbox: UnusedApplicationsEnvironmentConfiguration, production: UnusedApplicationsEnvironmentConfiguration)
case class UnusedApplicationsEnvironmentConfiguration(deleteUnusedApplicationsAfter: FiniteDuration, sendNotificationsInAdvance: Set[FiniteDuration])