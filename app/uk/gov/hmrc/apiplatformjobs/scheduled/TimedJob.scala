/*
 * Copyright 2022 HM Revenue & Customs
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

import net.ceedubs.ficus.Ficus._
import org.joda.time.{DateTime, DateTimeZone, Duration, LocalTime}
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.util.ApplicationLogger
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

abstract class TimedJob @Inject()(override val name: String,
                                  configuration: Configuration,
                                  mongo: ReactiveMongoComponent) extends ScheduledMongoJob with TimedJobConfigReaders with PrefixLogger {

  override val logPrefix: String = s"[$name]"
  override val lockKeeper: LockKeeper = mongoLockKeeper(mongo)

  val jobConfig: TimedJobConfig = configuration.underlying.as[TimedJobConfig](name)

  override val isEnabled: Boolean = jobConfig.enabled

  override def initialDelay: FiniteDuration = jobConfig.startTime match {
    case Some(startTime) => calculateInitialDelay(startTime.startTime)
    case _ => FiniteDuration(0, TimeUnit.MILLISECONDS)
  }

  override def interval: FiniteDuration = jobConfig.executionInterval.interval

  def calculateInitialDelay(timeOfFirstRun: LocalTime): FiniteDuration = {
    val currentDateTime = DateTime.now(DateTimeZone.UTC)
    val timeToday = timeOfFirstRun.toDateTimeToday(DateTimeZone.UTC)
    val nextInstanceOfTime = if (timeToday.isBefore(currentDateTime)) timeToday.plusDays(1) else timeToday
    val millisecondsToFirstRun = nextInstanceOfTime.getMillis - currentDateTime.getMillis

    FiniteDuration(millisecondsToFirstRun, TimeUnit.MILLISECONDS)
  }

  def mongoLockKeeper(mongo: ReactiveMongoComponent): LockKeeper = new LockKeeper {
    override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
    override def lockId: String = s"$name-Lock"
    override val forceLockReleaseAfter: Duration = Duration.standardHours(1)
  }

  override final def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logInfo("Starting scheduled job...")
    functionToExecute()
  }

  def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful]
}

class StartTime(val startTime: LocalTime) extends AnyVal
class ExecutionInterval(val interval: FiniteDuration) extends AnyVal

case class TimedJobConfig(startTime: Option[StartTime], executionInterval: ExecutionInterval, enabled: Boolean) {
  override def toString = s"TimedJobConfig{startTime=${startTime.getOrElse("Not Specified")} interval=${executionInterval.interval} enabled=$enabled}"
}

trait PrefixLogger extends ApplicationLogger {
  val logPrefix: String

  def logDebug(message: String) = logger.debug(s"$logPrefix $message")
  def logInfo(message: String) = logger.info(s"$logPrefix $message")
  def logWarn(message: String) = logger.warn(s"$logPrefix $message")
  def logError(message: String) = logger.error(s"$logPrefix $message")
  def logWarn(message: String, error: => Throwable) = logger.warn(s"$logPrefix $message", error)
  def logError(message: String, error: => Throwable) = logger.error(s"$logPrefix $message", error)
}
