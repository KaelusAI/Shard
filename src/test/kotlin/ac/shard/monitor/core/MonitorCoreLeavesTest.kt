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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MonitorCoreLeavesTest {
  @Test
  fun `formatDecimal rounds half up and honours the decimal count`() {
    assertEquals("95", formatDecimal(95.4, 0))
    assertEquals("96", formatDecimal(95.5, 0))
    assertEquals("12.50", formatDecimal(12.5, 2))
    assertEquals("0.1", formatDecimal(0.05, 1))
  }

  @Test
  fun `formatDecimal collapses near-zero noise to a plain zero`() {
    assertEquals("0.00", formatDecimal(0.00000001, 2))
    assertEquals("0.00", formatDecimal(-0.00000001, 2))
    assertEquals("-0.01", formatDecimal(-0.01, 2))
  }

  @Test
  fun `formatDecimal treats a negative decimal count as zero`() {
    assertEquals("95", formatDecimal(95.4, -3))
  }

  @Test
  fun `ticksToCycles rounds up and never returns zero`() {
    assertEquals(50, ticksToCycles(100, 2))
    assertEquals(1, ticksToCycles(1, 2))
    assertEquals(2, ticksToCycles(3, 2))
    assertEquals(1, ticksToCycles(0, 2))
  }

  @Test
  fun `component cache returns the same instance for the same input`() {
    val cache = ComponentCache()

    assertSame(cache.component("<red>hi</red>"), cache.component("<red>hi</red>"))
  }

  @Test
  fun `component cache keeps serving after it is full`() {
    val cache = ComponentCache(maxSize = 2)

    cache.component("a")
    cache.component("b")
    cache.component("c")

    assertEquals(cache.component("a"), cache.component("a"))
  }

  @Test
  fun `ping sampler holds a sample until the refresh interval elapses`() {
    val sampler = PingSampler()

    assertEquals("50", sampler.sample(50, refreshCycles = 3, bucketMs = 1))
    assertEquals("50", sampler.sample(90, refreshCycles = 3, bucketMs = 1))
    assertEquals("50", sampler.sample(90, refreshCycles = 3, bucketMs = 1))
    assertEquals("50", sampler.sample(90, refreshCycles = 3, bucketMs = 1))
    assertEquals("90", sampler.sample(90, refreshCycles = 3, bucketMs = 1))
  }

  @Test
  fun `ping sampler ignores movement inside one bucket`() {
    val sampler = PingSampler()

    assertEquals("50", sampler.sample(50, refreshCycles = 1, bucketMs = 10))
    sampler.sample(50, refreshCycles = 1, bucketMs = 10)

    assertEquals("50", sampler.sample(57, refreshCycles = 1, bucketMs = 10))
    sampler.sample(57, refreshCycles = 1, bucketMs = 10)

    assertEquals("60", sampler.sample(60, refreshCycles = 1, bucketMs = 10))
  }
}
