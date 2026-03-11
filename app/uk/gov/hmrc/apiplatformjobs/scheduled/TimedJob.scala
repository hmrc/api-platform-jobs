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

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Duration => _, _}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

import pureconfig.generic.ProductHint

import play.api.Configuration
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}

case class StartTime(val startTime: LocalTime)             extends AnyVal
case class ExecutionInterval(val interval: FiniteDuration) extends AnyVal

case class TimedJobConfig(startTime: Option[StartTime], executionInterval: ExecutionInterval, enabled: Boolean) {
  override def toString = s"TimedJobConfig{startTime=${startTime} interval=${executionInterval.interval} enabled=$enabled}"
}

abstract class TimedJob @Inject() (override val name: String, configuration: Configuration, val clock: Clock, lockRepository: LockRepository)
    extends ScheduledMongoJob
    with PrefixLogger
    with ClockNow {

  override val logPrefix: String        = s"[$name]"
  override val lockService: LockService = new MongoLockService(s"$name-Lock", lockRepository)

  import pureconfig._
  import pureconfig.generic.auto._
  import pureconfig.configurable._

  implicit val localTimeConvert: ConfigConvert[LocalTime] = localTimeConfigConvert(DateTimeFormatter.ISO_LOCAL_TIME)
  implicit def hint[A]: ProductHint[A]                    = ProductHint[A](ConfigFieldMapping(CamelCase, CamelCase))

  private val tjc = ConfigSource.fromConfig(configuration.underlying).at(name).load[TimedJobConfig]
  tjc.swap.map(x => logger.warn(x.toString()))

  val jobConfig: TimedJobConfig = tjc.toOption.get

  override val isEnabled: Boolean = jobConfig.enabled

  override val interval: FiniteDuration = jobConfig.executionInterval.interval

  if (interval.toMinutes < 5L) throw new IllegalArgumentException(s"$logPrefix: Interval must be at least 5 minutes")

  override def initialDelay: FiniteDuration = jobConfig.startTime match {
    case Some(startTime) => TimedJob.calculateInitialDelay(startTime.startTime, interval.toMinutes, now())
    case _               => FiniteDuration(0, TimeUnit.MILLISECONDS)
  }

  final override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logInfo("Starting scheduled job...")
    functionToExecute()
  }

  def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful]
}

object TimedJob {

  /*
   * We are using a configuredStartTime in order to avoid (or allow the avoidance of) running at midnight (or other busy point in time) just because the job deployed at 12 noon and runs every 4 hours
   * This code is not super efficient but hopefully clearer - it's only run once on startup.
   */
  def initialStartTime(configuredStartTime: LocalTime, intervalMinutes: Long, earliestStartDateTime: LocalDateTime): LocalDateTime = {
    var startTime: LocalDateTime = LocalDateTime.of(earliestStartDateTime.toLocalDate(), configuredStartTime)
    // Loop through until we are some part interval before "now"
    while (startTime.isAfter(earliestStartDateTime)) {
      startTime = startTime.minusMinutes(intervalMinutes)
    }
    // Then loop forward until we are one interval after "now"
    while (startTime.isBefore(earliestStartDateTime)) {
      startTime = startTime.plusMinutes(intervalMinutes)
    }
    startTime
  }

  def calculateInitialDelay(configuredStartTime: LocalTime, intervalMinutes: Long, timeNow: LocalDateTime): FiniteDuration = {
    val earliestStartDateTime: LocalDateTime = timeNow.plusMinutes(2) // Allow microservice to initialise completely
    val startTime                            = initialStartTime(configuredStartTime, intervalMinutes, earliestStartDateTime)
    FiniteDuration(ChronoUnit.MILLIS.between(timeNow, startTime), TimeUnit.MILLISECONDS)
  }
}

class MongoLockService @Inject() (override val lockId: String, repository: LockRepository) extends LockService {
  import scala.concurrent.duration._

  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

trait PrefixLogger extends ApplicationLogger {
  val logPrefix: String

  def logDebug(message: String)                      = logger.debug(s"$logPrefix $message")
  def logInfo(message: String)                       = logger.info(s"$logPrefix $message")
  def logWarn(message: String)                       = logger.warn(s"$logPrefix $message")
  def logError(message: String)                      = logger.error(s"$logPrefix $message")
  def logWarn(message: String, error: => Throwable)  = logger.warn(s"$logPrefix $message", error)
  def logError(message: String, error: => Throwable) = logger.error(s"$logPrefix $message", error)
}
