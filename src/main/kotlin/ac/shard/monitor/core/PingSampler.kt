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
package ac.shard.monitor.core

internal const val PING_UNAVAILABLE = -1

internal class PingSampler {
  private var lastBucket: Int = Int.MIN_VALUE
  private var lastValue: Int = PING_UNAVAILABLE
  private var cyclesSinceRefresh: Int = Int.MAX_VALUE

  fun sampleValue(ping: Int, refreshCycles: Int, bucketMs: Int): Int {
    val shouldRefresh = cyclesSinceRefresh >= refreshCycles || lastBucket == Int.MIN_VALUE
    if (!shouldRefresh) {
      cyclesSinceRefresh++
      return lastValue
    }

    cyclesSinceRefresh = 0
    val bucket = if (bucketMs <= 1) ping else ping / bucketMs
    if (bucket != lastBucket || lastValue == PING_UNAVAILABLE) {
      lastBucket = bucket
      lastValue = ping
    }
    return lastValue
  }

  fun sample(ping: Int, refreshCycles: Int, bucketMs: Int): String {
    val value = sampleValue(ping, refreshCycles, bucketMs)
    return if (value == PING_UNAVAILABLE) "" else value.toString()
  }
}
