/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * Shard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.connect

import ac.shard.scheduler.SchedulerService
import java.time.Instant
import java.util.logging.Logger

sealed interface DeviceFlowEnd {
  data class Approved(val result: PollResult.Approved) : DeviceFlowEnd

  data object Denied : DeviceFlowEnd

  data object Expired : DeviceFlowEnd

  data object TimedOut : DeviceFlowEnd
}

class DeviceFlowPoller(
  private val connectService: ConnectService,
  private val scheduler: SchedulerService,
  private val logger: Logger,
) {
  fun await(
    deviceCode: String,
    intervalSeconds: Long,
    deadlineEpochSec: Long,
    alive: () -> Boolean,
    onEnd: (DeviceFlowEnd) -> Unit,
  ) {
    Attempt(deviceCode, deadlineEpochSec, alive, onEnd).schedule(intervalSeconds)
  }

  private inner class Attempt(
    private val deviceCode: String,
    private val deadlineEpochSec: Long,
    private val alive: () -> Boolean,
    private val onEnd: (DeviceFlowEnd) -> Unit,
  ) {
    fun schedule(intervalSeconds: Long) {
      val interval = intervalSeconds.coerceAtLeast(1)
      scheduler.runLaterAsync({ poll(interval) }, interval * MILLIS_PER_SECOND)
    }

    private fun poll(interval: Long) {
      if (!alive()) return
      val expired = Instant.now().epochSecond >= deadlineEpochSec
      val result = if (expired) null else connectService.poll(deviceCode)
      if (alive()) handle(result, interval)
    }

    private fun handle(result: PollResult?, interval: Long) {
      when (result) {
        null -> onEnd(DeviceFlowEnd.TimedOut)
        PollResult.Pending -> schedule(interval)
        is PollResult.SlowDown -> schedule(maxOf(interval, result.intervalSeconds))
        is PollResult.Error -> {
          logger.fine("[Connect] poll error: ${result.message}")
          schedule(interval)
        }
        is PollResult.Approved -> onEnd(DeviceFlowEnd.Approved(result))
        PollResult.Denied -> onEnd(DeviceFlowEnd.Denied)
        PollResult.Expired -> onEnd(DeviceFlowEnd.Expired)
      }
    }
  }

  private companion object {
    const val MILLIS_PER_SECOND = 1000L
  }
}
