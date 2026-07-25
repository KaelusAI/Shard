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

class TrendTrackerTest {
  @Test
  fun `the first sample has nothing to compare against`() {
    assertEquals(0.0, TrendTracker(threshold = 0.005, decayCycles = 0).update(0.42))
  }

  @Test
  fun `a move above the threshold becomes the trend`() {
    val tracker = TrendTracker(threshold = 0.005, decayCycles = 0)
    tracker.update(0.40)

    assertEquals(0.10, tracker.update(0.50), 1e-9)
  }

  @Test
  fun `a move below the threshold reads as flat`() {
    val tracker = TrendTracker(threshold = 0.05, decayCycles = 0)
    tracker.update(0.40)

    assertEquals(0.0, tracker.update(0.41))
  }

  @Test
  fun `a held probability keeps the last trend until it decays`() {
    val tracker = TrendTracker(threshold = 0.005, decayCycles = 3)
    tracker.update(0.40)
    tracker.update(0.50)

    assertEquals(0.10, tracker.update(0.50), 1e-9)
    assertEquals(0.10, tracker.update(0.50), 1e-9)
    assertEquals(0.0, tracker.update(0.50))
  }

  @Test
  fun `decay is disabled when the cycle count is zero`() {
    val tracker = TrendTracker(threshold = 0.005, decayCycles = 0)
    tracker.update(0.40)
    tracker.update(0.50)

    repeat(100) { tracker.update(0.50) }

    assertEquals(0.10, tracker.update(0.50), 1e-9)
  }

  @Test
  fun `reset forgets the previous probability`() {
    val tracker = TrendTracker(threshold = 0.005, decayCycles = 0)
    tracker.update(0.40)
    tracker.update(0.50)
    tracker.reset()

    assertEquals(0.0, tracker.update(0.90))
  }
}
