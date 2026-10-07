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

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class ProbeBackoff(
  private val clock: () -> Long,
  private val random: Random = Random.Default,
) {
  private val inFlight = AtomicBoolean()

  @Volatile private var attempts = 0

  @Volatile private var nextAt = 0L

  fun tryStart(): Boolean = clock() >= nextAt && inFlight.compareAndSet(false, true)

  fun finished(profileArrived: Boolean) {
    if (profileArrived) {
      settled()
    } else {
      val step = FIRST_RETRY_MS shl attempts.coerceAtMost(DOUBLINGS)
      attempts++
      val jitter = 1.0 + (random.nextDouble() * 2 - 1) * JITTER
      nextAt = clock() + (step.coerceAtMost(MAX_RETRY_MS) * jitter).toLong()
    }
    inFlight.set(false)
  }

  fun settled() {
    attempts = 0
    nextAt = 0L
  }

  fun reset() {
    settled()
    inFlight.set(false)
  }

  companion object {
    const val FIRST_RETRY_MS = 5_000L
    const val MAX_RETRY_MS = 60_000L
    const val JITTER = 0.2
    private const val DOUBLINGS = 4
  }
}
