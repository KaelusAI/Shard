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
package ac.shard.data

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DataBufferTest {

  private fun TickBuffer.writeTick(tickIndex: Long, sequenceId: Int = 1, attack: Boolean = false) {
    val slot = currentSlot()
    slot.tickIndex = tickIndex
    slot.sequenceId = sequenceId
    if (attack) markAttack()
    advance()
  }

  @Test
  fun `extracts full window around attack`() {
    val buffer = TickBuffer(16)
    repeat(4) { buffer.writeTick(it.toLong()) }
    buffer.writeTick(4, attack = true)
    repeat(3) { buffer.writeTick((5 + it).toLong()) }

    val window = buffer.extractWindow(preWindow = 4, postWindow = 4, ticksSinceAttack = 4)

    assertNotNull(window)
    assertEquals(8, window.size)
    assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), window.map { it.tickIndex })
    assertEquals(-4, window.first().ticksToAttack.toInt())
    assertEquals(0, window[4].ticksToAttack.toInt())
    assertEquals(3, window.last().ticksToAttack.toInt())
  }

  @Test
  fun `pre window is capped by written history`() {
    val buffer = TickBuffer(16)
    buffer.writeTick(0)
    buffer.writeTick(1)
    buffer.writeTick(2, attack = true)

    val window = buffer.extractWindow(preWindow = 4, postWindow = 1, ticksSinceAttack = 1)

    assertNotNull(window)
    assertEquals(listOf(0L, 1L, 2L), window.map { it.tickIndex })
    assertEquals(-2, window.first().ticksToAttack.toInt())
    assertEquals(0, window.last().ticksToAttack.toInt())
  }

  @Test
  fun `sequence break inside window drops it`() {
    val buffer = TickBuffer(16)
    repeat(2) { buffer.writeTick(it.toLong(), sequenceId = 1) }
    repeat(2) { buffer.writeTick((2 + it).toLong(), sequenceId = 2) }
    buffer.writeTick(4, sequenceId = 2, attack = true)
    repeat(3) { buffer.writeTick((5 + it).toLong(), sequenceId = 2) }

    val window = buffer.extractWindow(preWindow = 4, postWindow = 4, ticksSinceAttack = 4)

    assertNull(window)
  }

  @Test
  fun `no attack means no window`() {
    val buffer = TickBuffer(16)
    repeat(8) { buffer.writeTick(it.toLong()) }

    assertNull(buffer.extractWindow(preWindow = 4, postWindow = 4, ticksSinceAttack = 4))
  }

  @Test
  fun `window wraps around ring boundary`() {
    val buffer = TickBuffer(8)
    repeat(12) { buffer.writeTick(it.toLong()) }
    buffer.writeTick(12, attack = true)
    repeat(2) { buffer.writeTick((13 + it).toLong()) }

    val window = buffer.extractWindow(preWindow = 3, postWindow = 3, ticksSinceAttack = 3)

    assertNotNull(window)
    assertEquals(listOf(9L, 10L, 11L, 12L, 13L, 14L), window.map { it.tickIndex })
  }

  @Test
  fun `window copies are detached from ring slots`() {
    val buffer = TickBuffer(8)
    buffer.writeTick(0)
    buffer.writeTick(1, attack = true)
    buffer.writeTick(2)

    val window = buffer.extractWindow(preWindow = 1, postWindow = 2, ticksSinceAttack = 2)
    assertNotNull(window)
    val originalTick = window[0].tickIndex

    repeat(8) { buffer.writeTick((100 + it).toLong()) }

    assertEquals(originalTick, window[0].tickIndex)
  }

  @Test
  fun `window larger than capacity is dropped instead of corrupting`() {
    val buffer = TickBuffer(8)
    repeat(20) { buffer.writeTick(it.toLong()) }
    buffer.writeTick(20, attack = true)
    repeat(128) { buffer.writeTick((21 + it).toLong()) }

    assertNull(buffer.extractWindow(preWindow = 128, postWindow = 128, ticksSinceAttack = 128))
  }

  @Test
  fun `oversized pre window is clamped to untouched ring slots`() {
    val buffer = TickBuffer(8)
    repeat(20) { buffer.writeTick(it.toLong()) }
    buffer.writeTick(20, attack = true)
    repeat(4) { buffer.writeTick((21 + it).toLong()) }

    val window = buffer.extractWindow(preWindow = 128, postWindow = 4, ticksSinceAttack = 4)

    assertNotNull(window)
    assertEquals(7, window.size)
    assertEquals(listOf(17L, 18L, 19L, 20L, 21L, 22L, 23L), window.map { it.tickIndex })
    assertEquals(-3, window.first().ticksToAttack.toInt())
    assertEquals(0, window[3].ticksToAttack.toInt())
  }

  @Test
  fun `anchor pointing at a never-captured slot drops the window`() {
    val buffer = TickBuffer(8)
    buffer.advance()
    buffer.advance()
    assertNull(buffer.extractWindow(1, 1, 1, attackIdx = 0))
  }

  @Test
  fun `attack too early in a fresh session drops the window`() {
    val buffer = TickBuffer(16)
    repeat(8) { buffer.writeTick(it.toLong(), sequenceId = 1) }
    buffer.resetForSession()

    buffer.writeTick(100, sequenceId = 2, attack = true)
    buffer.writeTick(101, sequenceId = 2)
    buffer.writeTick(102, sequenceId = 2)

    val window = buffer.extractWindow(preWindow = 4, postWindow = 2, ticksSinceAttack = 2)

    assertNull(window)
  }

  @Test
  fun `fresh session builds a window once enough pre history exists`() {
    val buffer = TickBuffer(16)
    repeat(8) { buffer.writeTick(it.toLong(), sequenceId = 1) }
    buffer.resetForSession()

    buffer.writeTick(100, sequenceId = 2)
    buffer.writeTick(101, sequenceId = 2)
    buffer.writeTick(102, sequenceId = 2, attack = true)
    buffer.writeTick(103, sequenceId = 2)

    val window = buffer.extractWindow(preWindow = 2, postWindow = 2, ticksSinceAttack = 2)

    assertNotNull(window)
    assertEquals(listOf(100L, 101L, 102L, 103L), window.map { it.tickIndex })
    assertEquals(0, window[2].ticksToAttack.toInt())
  }
}
