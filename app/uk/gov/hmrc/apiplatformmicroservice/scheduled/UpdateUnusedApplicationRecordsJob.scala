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

package uk.gov.hmrc.apiplatformmicroservice.scheduled
import java.util.UUID

import javax.inject.{Inject, Named, Singleton}
import net.ceedubs.ficus.Ficus._
import org.joda.time.DateTime
import play.api.{Configuration, Logger}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformmicroservice.connectors.{ThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformmicroservice.models.Environment.Environment
import uk.gov.hmrc.apiplatformmicroservice.models.{Administrator, ApplicationUsageDetails, Environment, UnusedApplication}
import uk.gov.hmrc.apiplatformmicroservice.repository.UnusedApplicationsRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

abstract class UpdateUnusedApplicationRecordsJob (environment: Environment,
                                                  thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                  thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
                                                  unusedApplicationsRepository: UnusedApplicationsRepository,
                                                  configuration: Configuration,
                                                  mongo: ReactiveMongoComponent)
  extends TimedJob(s"UpdateUnusedApplicationsRecords-$environment", configuration, mongo) {

  val updateUnusedApplicationRecordsJobConfig: UpdateUnusedApplicationRecordsJobConfig =
    configuration.underlying.as[UpdateUnusedApplicationRecordsJobConfig](name)
  val DeleteUnusedApplicationsAfter: FiniteDuration = configuration.underlying.as[FiniteDuration]("deleteUnusedApplicationsAfter")

  def notificationCutoffDate(): DateTime =
    DateTime.now
      .minus(DeleteUnusedApplicationsAfter.toMillis)
      .plus(updateUnusedApplicationRecordsJobConfig.notifyDeletionPendingInAdvance.toMillis)

  def calculateScheduledDeletionDate(lastInteractionDate: DateTime): DateTime = lastInteractionDate.plus(DeleteUnusedApplicationsAfter.toMillis)

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    def unknownApplications(knownApplications: List[UnusedApplication], currentUnusedApplications: List[ApplicationUsageDetails]) = {
      val knownApplicationIds: Set[UUID] = knownApplications.map(_.applicationId).toSet
      currentUnusedApplications.filterNot(app => knownApplicationIds.contains(app.applicationId))
    }

    for {
      knownApplications <- unusedApplicationsRepository.applicationsByEnvironment(environment)
      currentUnusedApplications <- thirdPartyApplicationConnector.applicationsLastUsedBefore(notificationCutoffDate())

      newUnusedApplications: Seq[ApplicationUsageDetails] = unknownApplications(knownApplications, currentUnusedApplications)
      _ = Logger.info(s"[UpdateUnusedApplicationRecordsJob] Found ${newUnusedApplications.size} new unused applications since last update")

      notifiedApplications <- sendEmailNotifications(newUnusedApplications)
      _ = if(notifiedApplications.nonEmpty) unusedApplicationsRepository.bulkInsert(notifiedApplications)
    } yield RunningOfJobSuccessful
  }

  def sendEmailNotifications(unusedApplications: Seq[ApplicationUsageDetails])(implicit executionContext: ExecutionContext): Future[Seq[UnusedApplication]] = {
    def administratorDetails(adminEmails: Set[String]): Future[Map[String, Administrator]] = {
      if (adminEmails.isEmpty) {
        Future.successful(Map.empty)
      } else {
        for {
          verifiedAdmins <- thirdPartyDeveloperConnector.fetchVerifiedDevelopers(adminEmails)
        } yield verifiedAdmins.map(admin => admin._1 -> Administrator(admin._1, admin._2, admin._3)).toMap
      }
    }

    def verifiedAdminDetails(adminEmails: Set[String], verifiedAdmins: Map[String, Administrator]): Set[Administrator] =
      adminEmails.intersect(verifiedAdmins.keySet).flatMap(verifiedAdmins.get)

    if (unusedApplications.nonEmpty) {
      val adminEmailAddresses: Set[String] = unusedApplications.flatMap(_.administrators).toSet

      for {
        adminDetails <- administratorDetails(adminEmailAddresses)
        appDetails = unusedApplications.map(app => {
          val lastInteractionDate = app.lastAccessDate.getOrElse(app.creationDate)
          UnusedApplication(
            app.applicationId,
            app.applicationName,
            verifiedAdminDetails(adminEmailAddresses, adminDetails),
            environment,
            lastInteractionDate,
            calculateScheduledDeletionDate(lastInteractionDate))
        })
      } yield appDetails
    } else Future.successful(Seq.empty)
  }
}

@Singleton
class UpdateUnusedSandboxApplicationRecordJob @Inject()(@Named("tpa-sandbox") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                        thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
                                                        unusedApplicationsRepository: UnusedApplicationsRepository,
                                                        configuration: Configuration,
                                                        mongo: ReactiveMongoComponent)
  extends UpdateUnusedApplicationRecordsJob(
    Environment.SANDBOX, thirdPartyApplicationConnector, thirdPartyDeveloperConnector, unusedApplicationsRepository, configuration, mongo)

@Singleton
class UpdateUnusedProductionApplicationRecordJob @Inject()(@Named("tpa-production") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                           thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
                                                           unusedApplicationsRepository: UnusedApplicationsRepository,
                                                           configuration: Configuration,
                                                           mongo: ReactiveMongoComponent)
  extends UpdateUnusedApplicationRecordsJob(
    Environment.PRODUCTION, thirdPartyApplicationConnector, thirdPartyDeveloperConnector, unusedApplicationsRepository, configuration, mongo)

case class UpdateUnusedApplicationRecordsJobConfig(notifyDeletionPendingInAdvance: FiniteDuration, externalEnvironmentName: String)