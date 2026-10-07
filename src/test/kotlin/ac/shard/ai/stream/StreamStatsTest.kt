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

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamStatsTest {
  private var now = 1_000_000L
  private val stats = StreamStats { now }

  @Test
  fun `averages and peaks cover the last minute`() {
    stats.onBatch(4, concurrent = 1, queued = 0, queuedBytes = 0)
    stats.onBatch(2, concurrent = 3, queued = 5, queuedBytes = 900)
    stats.onReply(100)
    now += 1_000
    stats.onBatch(6, concurrent = 2, queued = 1, queuedBytes = 100)
    stats.onReply(300)
    stats.onFailure()

    val snapshot = stats.snapshot()

    assertEquals(12.0 / StreamStats.WINDOW_SECONDS, snapshot.windowsPerSecond)
    assertEquals(6, snapshot.peakWindowsPerSecond)
    assertEquals(4.0, snapshot.averageBatch)
    assertEquals(6, snapshot.largestBatch)
    assertEquals(3, snapshot.peakConcurrent)
    assertEquals(5, snapshot.peakQueued)
    assertEquals(900, snapshot.peakQueuedBytes)
    assertEquals(200, snapshot.averageRttMs)
    assertEquals(1, snapshot.failures)
  }

  @Test
  fun `seconds older than a minute are forgotten`() {
    stats.onBatch(50, concurrent = 16, queued = 300, queuedBytes = 1_000_000)
    now += StreamStats.WINDOW_SECONDS * 1_000L
    stats.onBatch(1, concurrent = 1, queued = 0, queuedBytes = 0)

    val snapshot = stats.snapshot()

    assertEquals(1, snapshot.largestBatch)
    assertEquals(1, snapshot.peakConcurrent)
    assertEquals(0, snapshot.peakQueued)
  }
}
