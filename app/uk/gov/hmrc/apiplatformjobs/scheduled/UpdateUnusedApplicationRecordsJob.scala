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

import java.time.temporal.ChronoUnit
import java.time.{Clock, LocalDate, LocalDateTime}
import java.util.UUID
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import play.api.Configuration
import uk.gov.hmrc.mongo.lock.LockRepository

import uk.gov.hmrc.apiplatformjobs.connectors.{ThirdPartyApplicationConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.apiplatformjobs.models.Environment.{Environment, PRODUCTION, SANDBOX}
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository

abstract class UpdateUnusedApplicationRecordsJob(
    thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    environment: Environment,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
) extends UnusedApplicationsJob("UpdateUnusedApplicationRecordsJob", environment, configuration, clock, lockRepository) {

  /** The date we should use to find applications that have not been used since. This should be far enough in advance that all required notifications can be sent out.
    */
  def notificationCutoffDate(): LocalDateTime =
    LocalDateTime
      .now(clock)
      .minus(deleteUnusedApplicationsAfter(environment).toMillis, ChronoUnit.MILLIS)
      .plus(firstNotificationInAdvance(environment).toMillis, ChronoUnit.MILLIS)

  /** The dates we will be sending notifications out to Admins */
  def calculateNotificationDates(scheduledDeletionDate: LocalDate): Seq[LocalDate] =
    sendNotificationsInAdvance(environment)
      .map(inAdvance => scheduledDeletionDate.minusDays(inAdvance.toDays.toInt))
      .toSeq

  /** The date we will be deleting the application */
  def calculateScheduledDeletionDate(lastInteractionDate: LocalDateTime): LocalDate =
    lastInteractionDate
      .plus(deleteUnusedApplicationsAfter(environment).toMillis, ChronoUnit.MILLIS)
      .toLocalDate

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    def applicationsToUpdate(knownApplications: List[UnusedApplication], currentUnusedApplications: List[ApplicationUsageDetails]): (Set[UUID], Set[UUID]) = {

      val knownApplicationIds: Set[UUID]         = knownApplications.map(_.applicationId).toSet
      val currentUnusedApplicationIds: Set[UUID] = currentUnusedApplications.map(_.applicationId).toSet

      (currentUnusedApplicationIds.diff(knownApplicationIds), knownApplicationIds.diff(currentUnusedApplicationIds))
    }

    def verifiedAdministratorDetails(adminEmails: Set[String]): Future[Map[String, Administrator]] = {
      if (adminEmails.isEmpty) {
        Future.successful(Map.empty)
      } else {
        for {
          verifiedAdmins <- thirdPartyDeveloperConnector.fetchVerifiedDevelopers(adminEmails)
        } yield verifiedAdmins.map(admin => admin.email -> Administrator(emailAddress = admin.email, firstName = admin.firstName, lastName = admin.lastName)).toMap
      }
    }

    for {
      knownApplications                      <- unusedApplicationsRepository.unusedApplications(environment)
      currentUnusedApplications              <- thirdPartyApplicationConnector.applicationsLastUsedBefore(notificationCutoffDate())
      updatesRequired: (Set[UUID], Set[UUID]) = applicationsToUpdate(knownApplications, currentUnusedApplications)

      _                                                              = logInfo(s"Found ${updatesRequired._1.size} new unused applications since last update")
      applicationsToAdd                                              = currentUnusedApplications.filter(app => updatesRequired._1.contains(app.applicationId))
      verifiedApplicationAdministrators: Map[String, Administrator] <- verifiedAdministratorDetails(applicationsToAdd.flatMap(_.administrators).toSet)
      newUnusedApplicationRecords: Seq[UnusedApplication]            = applicationsToAdd.map(unusedApplicationRecord(_, verifiedApplicationAdministrators))
      _                                                              = if (newUnusedApplicationRecords.nonEmpty) unusedApplicationsRepository.bulkInsert(newUnusedApplicationRecords)

      _ = logInfo(s"Found ${updatesRequired._2.size} applications that have been used since last update")
      _ = if (updatesRequired._2.nonEmpty) Future.sequence(updatesRequired._2.map(unusedApplicationsRepository.deleteUnusedApplicationRecord(environment, _)))
    } yield RunningOfJobSuccessful
  }

  def unusedApplicationRecord(applicationUsageDetails: ApplicationUsageDetails, verifiedAdministratorDetails: Map[String, Administrator]): UnusedApplication = {
    val verifiedApplicationAdministrators =
      applicationUsageDetails.administrators.intersect(verifiedAdministratorDetails.keySet).flatMap(verifiedAdministratorDetails.get)
    val lastInteractionDate               = applicationUsageDetails.lastAccessDate.getOrElse(applicationUsageDetails.creationDate)
    val scheduledDeletionDate             = calculateScheduledDeletionDate(lastInteractionDate)
    val notificationSchedule              = calculateNotificationDates(scheduledDeletionDate)

    UnusedApplication(
      applicationUsageDetails.applicationId,
      applicationUsageDetails.applicationName,
      verifiedApplicationAdministrators.toSeq,
      environment,
      lastInteractionDate,
      notificationSchedule,
      scheduledDeletionDate
    )
  }

}

@Singleton
class UpdateUnusedSandboxApplicationRecordsJob @Inject() (
    @Named("tpa-sandbox") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
) extends UpdateUnusedApplicationRecordsJob(
      thirdPartyApplicationConnector,
      thirdPartyDeveloperConnector,
      unusedApplicationsRepository,
      SANDBOX,
      configuration,
      clock,
      lockRepository
    )

@Singleton
class UpdateUnusedProductionApplicationRecordsJob @Inject() (
    @Named("tpa-production") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
) extends UpdateUnusedApplicationRecordsJob(
      thirdPartyApplicationConnector,
      thirdPartyDeveloperConnector,
      unusedApplicationsRepository,
      PRODUCTION,
      configuration,
      clock,
      lockRepository
    )
