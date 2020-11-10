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

package uk.gov.hmrc.apiplatformjobs.connectors

import javax.inject.{Inject, Singleton}

import scala.concurrent.ExecutionContext

@Singleton
class ApiPlatformMicroserviceConnector @Inject()(config: ApiPlatformMicroserviceConnectorConfig)(implicit ec: ExecutionContext) {
  /*
   * APIS-5081: Connector was previously used as part of MigrateEmailPreferences job - that's no longer in use, but we're retaining the connector (and its
   * associated configuration) as we're pretty sure we'll be needing it again in the near future.
   */
}

case class ApiPlatformMicroserviceConnectorConfig(baseUrl: String)
