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
package ac.shard.ai.stream

class StreamStats(private val clock: () -> Long = System::currentTimeMillis) {
  @Suppress("LongParameterList")
  class Snapshot(
    val windowsPerSecond: Double,
    val peakWindowsPerSecond: Long,
    val averageBatch: Double,
    val largestBatch: Int,
    val peakConcurrent: Int,
    val peakQueued: Int,
    val peakQueuedBytes: Long,
    val averageRttMs: Long,
    val failures: Long,
  )

  private class Second {
    var at = -1L
    var windows = 0L
    var batches = 0L
    var largestBatch = 0
    var peakConcurrent = 0
    var peakQueued = 0
    var peakQueuedBytes = 0L
    var rttSum = 0L
    var replies = 0L
    var failures = 0L

    fun reset(second: Long) {
      at = second
      windows = 0
      batches = 0
      largestBatch = 0
      peakConcurrent = 0
      peakQueued = 0
      peakQueuedBytes = 0
      rttSum = 0
      replies = 0
      failures = 0
    }
  }

  private val ring = Array(WINDOW_SECONDS) { Second() }

  @Synchronized
  fun onBatch(size: Int, concurrent: Int, queued: Int, queuedBytes: Long) {
    val s = current()
    s.windows += size
    s.batches++
    s.largestBatch = maxOf(s.largestBatch, size)
    s.peakConcurrent = maxOf(s.peakConcurrent, concurrent)
    s.peakQueued = maxOf(s.peakQueued, queued)
    s.peakQueuedBytes = maxOf(s.peakQueuedBytes, queuedBytes)
  }

  @Synchronized
  fun onReply(rttMs: Long) {
    val s = current()
    s.rttSum += rttMs
    s.replies++
  }

  @Synchronized
  fun onFailure() {
    current().failures++
  }

  @Synchronized
  fun snapshot(): Snapshot {
    val now = clock() / MILLIS_PER_SECOND
    val live = ring.filter { it.at > now - WINDOW_SECONDS && it.at <= now }
    val windows = live.sumOf { it.windows }
    val batches = live.sumOf { it.batches }
    val replies = live.sumOf { it.replies }
    return Snapshot(
      windowsPerSecond = windows.toDouble() / WINDOW_SECONDS,
      peakWindowsPerSecond = live.maxOfOrNull { it.windows } ?: 0L,
      averageBatch = if (batches == 0L) 0.0 else windows.toDouble() / batches,
      largestBatch = live.maxOfOrNull { it.largestBatch } ?: 0,
      peakConcurrent = live.maxOfOrNull { it.peakConcurrent } ?: 0,
      peakQueued = live.maxOfOrNull { it.peakQueued } ?: 0,
      peakQueuedBytes = live.maxOfOrNull { it.peakQueuedBytes } ?: 0L,
      averageRttMs = if (replies == 0L) 0L else live.sumOf { it.rttSum } / replies,
      failures = live.sumOf { it.failures },
    )
  }

  private fun current(): Second {
    val second = clock() / MILLIS_PER_SECOND
    val slot = ring[(second % WINDOW_SECONDS).toInt()]
    if (slot.at != second) slot.reset(second)
    return slot
  }

  companion object {
    const val WINDOW_SECONDS = 60
    private const val MILLIS_PER_SECOND = 1_000L
  }
}
