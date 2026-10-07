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

import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProbeBackoffTest {

  private var now = 0L

  private val probes =
    ProbeBackoff(
      { now },
      object : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextDouble() = 0.5
      },
    )

  @Test
  fun `only one probe is in flight at a time`() {
    assertTrue(probes.tryStart())
    assertFalse(probes.tryStart())
    probes.finished(profileArrived = true)
    assertTrue(probes.tryStart())
  }

  @Test
  fun `failed probes back off from five seconds up to a minute`() {
    val waits = mutableListOf<Long>()
    repeat(7) {
      assertTrue(probes.tryStart())
      val start = now
      probes.finished(profileArrived = false)
      while (!probes.tryStart()) now += 100
      waits += now - start
      probes.finished(profileArrived = false)
      now += ProbeBackoff.MAX_RETRY_MS * 2
    }
    assertTrue(waits.first() in 5_000L..5_100L, "first retry after about 5 s, got ${waits.first()}")
    assertTrue(waits.last() in 60_000L..60_100L, "retries cap at a minute, got ${waits.last()}")
  }

  @Test
  fun `a profile resets the schedule`() {
    probes.tryStart()
    probes.finished(profileArrived = false)
    probes.reset()
    assertTrue(probes.tryStart())
  }

  @Test
  fun `settling keeps the in flight guard and a reset clears it`() {
    assertTrue(probes.tryStart())
    probes.settled()
    assertFalse(probes.tryStart())
    probes.reset()
    assertTrue(probes.tryStart())
  }
}
