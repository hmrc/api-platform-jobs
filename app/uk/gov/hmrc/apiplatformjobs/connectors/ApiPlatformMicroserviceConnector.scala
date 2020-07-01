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
import uk.gov.hmrc.apiplatformjobs.models.APIDefinition
import uk.gov.hmrc.http._
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiPlatformMicroserviceConnector @Inject()(config: ApiPlatformMicroserviceConnectorConfig, http: HttpClient)(implicit ec: ExecutionContext) {

  def fetchSubscribedApiDefinitionsForCollaborator(collaboratorEmail: String)(implicit hc: HeaderCarrier): Future[Seq[APIDefinition]] = {
    http.GET[Seq[APIDefinition]](s"${config.baseUrl}/combined-api-definitions/subscribed", Seq("collaboratorEmail" -> collaboratorEmail))
  }
}

case class ApiPlatformMicroserviceConnectorConfig(baseUrl: String)
