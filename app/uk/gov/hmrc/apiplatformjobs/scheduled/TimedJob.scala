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

import java.time._
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, duration}

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

  override def initialDelay: FiniteDuration = jobConfig.startTime match {
    case Some(startTime) => calculateInitialDelay(startTime.startTime)
    case _               => FiniteDuration(0, TimeUnit.MILLISECONDS)
  }

  override def interval: FiniteDuration = jobConfig.executionInterval.interval

  def calculateInitialDelay(timeOfFirstRun: LocalTime): FiniteDuration = {
    val currentDateTime        = instant()
    val timeToday              = LocalDateTime.of(now().toLocalDate, timeOfFirstRun).toInstant(ZoneOffset.UTC)
    val nextInstanceOfTime     = if (timeToday.isBefore(currentDateTime)) timeToday.plus(Duration.ofDays(1)) else timeToday
    val millisecondsToFirstRun = nextInstanceOfTime.toEpochMilli - currentDateTime.toEpochMilli
    millisecondsToFirstRun.milliseconds
  }

  final override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logInfo("Starting scheduled job...")
    functionToExecute()
  }

  def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful]
}

class MongoLockService @Inject() (override val lockId: String, repository: LockRepository) extends LockService {

  override val lockRepository: LockRepository = repository
  override val ttl: duration.Duration         = 1.hours
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
