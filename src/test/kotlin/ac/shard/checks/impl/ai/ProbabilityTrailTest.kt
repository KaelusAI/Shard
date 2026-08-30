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
package ac.shard.checks.impl.ai

import ac.shard.utils.Sparkline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbabilityTrailTest {

  @Test
  fun `the tail keeps the newest points once the ring has wrapped`() {
    val trail = ProbabilityTrail(capacity = 4)
    listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).forEach(trail::record)

    val tail = trail.tail(4).map { ProbabilityTrail.probabilityOf(it) }

    assertEquals(4, tail.size)
    assertTrue(abs(tail.first() - 0.3) < 0.01, "the two oldest points fell out, got $tail")
    assertTrue(abs(tail.last() - 0.6) < 0.01, "the newest point comes last, got $tail")
  }

  @Test
  fun `asking for more than was recorded returns only what there is`() {
    val trail = ProbabilityTrail(capacity = 100)
    repeat(3) { trail.record(0.5) }

    assertEquals(3, trail.tail(60).size)
    assertEquals(0, ProbabilityTrail(capacity = 100).tail(60).size)
  }

  @Test
  fun `a byte holds the probability closely enough for every threshold in the rules`() {
    listOf(0.02, 0.20, 0.75, 0.80, 0.90, 0.96).forEach { probability ->
      val back = ProbabilityTrail.probabilityOf(ProbabilityTrail.quantize(probability))
      assertTrue(
        abs(back - probability) < 0.005,
        "a threshold must survive the round trip: $probability came back as $back",
      )
    }
  }

  @Test
  fun `the sparkline is drawn on a fixed scale, so a quiet session stays low`() {
    val quiet = ProbabilityTrail(capacity = 60)
    repeat(60) { quiet.record(0.05) }
    val cheating = ProbabilityTrail(capacity = 60)
    repeat(60) { cheating.record(0.95) }

    val quietLine = Sparkline.of(quiet.tail(60), 15)
    val cheatingLine = Sparkline.of(cheating.tail(60), 15)

    assertEquals("▁".repeat(15), quietLine)
    assertEquals("█".repeat(15), cheatingLine)
  }

  @Test
  fun `a single spike is visible next to the quiet part around it`() {
    val trail = ProbabilityTrail(capacity = 60)
    repeat(30) { trail.record(0.05) }
    repeat(4) { trail.record(0.95) }
    repeat(26) { trail.record(0.05) }

    val line = Sparkline.of(trail.tail(60), 15)

    assertEquals(15, line.length)
    assertTrue(line.contains('█'), "the spike must survive the downscale: $line")
    assertTrue(line.startsWith("▁"), "the quiet start stays quiet: $line")
  }
}
