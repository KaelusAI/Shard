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

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ViolationBufferTest {

  private val settings =
    ViolationBuffer.Settings(
      cheatProbability = 0.9,
      legitProbability = 0.1,
      multiplier = 100.0,
      decrease = 1.0,
    )

  @Test
  fun `a high probability grows the buffer by the distance past the threshold`() {
    val buffer = ViolationBuffer()

    buffer.feed(0.95, settings)

    assertEquals(5.0, buffer.value, 1e-9)
  }

  @Test
  fun `a low probability decays the buffer and never below zero`() {
    val buffer = ViolationBuffer()
    buffer.feed(0.92, settings)

    buffer.feed(0.0, settings)
    assertEquals(1.0, buffer.value, 1e-9)

    repeat(5) { buffer.feed(0.0, settings) }
    assertEquals(0.0, buffer.value, 1e-9)
  }

  @Test
  fun `a probability between the thresholds leaves the buffer alone`() {
    val buffer = ViolationBuffer()
    buffer.feed(0.95, settings)
    val before = buffer.value

    buffer.feed(0.5, settings)

    assertEquals(before, buffer.value, 1e-9)
  }

  @Test
  fun `restore takes the value as given, so relogging cannot shrink a buffer`() {
    val buffer = ViolationBuffer()

    buffer.restore(saved = 500.0)

    assertEquals(500.0, buffer.value, 1e-9)
  }

  @Test
  fun `restore never lowers a buffer the live session already earned`() {
    val buffer = ViolationBuffer()
    buffer.feed(1.0, settings)

    buffer.restore(saved = 2.0)

    assertEquals(10.0, buffer.value, 1e-9)
  }

  @Test
  fun `a negative saved value cannot drive the buffer below zero`() {
    val buffer = ViolationBuffer()

    buffer.restore(saved = -100.0)

    assertEquals(0.0, buffer.value, 1e-9)
  }
}
