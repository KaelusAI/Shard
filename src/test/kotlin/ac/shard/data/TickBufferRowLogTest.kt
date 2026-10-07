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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TickBufferRowLogTest {

  private fun TickBuffer.row(tickIndex: Long, sequenceId: Int = 1, attack: Boolean = false) {
    captureRaw {
      it.tickIndex = tickIndex
      it.sequenceId = sequenceId
    }
    if (attack) markAttack()
    advance()
  }

  @Test
  fun `row seq starts at one and follows captured rows`() {
    val buffer = TickBuffer(8)
    assertEquals(1L, buffer.currentSeq())
    repeat(3) { buffer.row(100L + it) }
    assertEquals(4L, buffer.currentSeq())
    assertEquals(100L, buffer.rowAt(1).tickIndex)
    assertEquals(102L, buffer.rowAt(3).tickIndex)
  }

  @Test
  fun `holes in tick index do not consume row seq`() {
    val buffer = TickBuffer(8)
    buffer.row(10)
    buffer.row(13)
    buffer.row(20)
    assertEquals(listOf(10L, 13L, 20L), (1L..3L).map { buffer.rowAt(it).tickIndex })
  }

  @Test
  fun `wraparound keeps only the last capacity rows`() {
    val buffer = TickBuffer(5)
    repeat(12) { buffer.row(it.toLong()) }
    assertEquals(13L, buffer.currentSeq())
    assertEquals(9L, buffer.oldestSeq())
    assertTrue(buffer.holds(9, 12))
    assertFalse(buffer.holds(8, 12))
    assertEquals(11L, buffer.rowAt(12).tickIndex)
    assertEquals(8L, buffer.rowAt(9).tickIndex)
    assertFailsWith<IllegalArgumentException> { buffer.rowAt(8) }
  }

  @Test
  fun `holds rejects empty future and reversed ranges`() {
    val buffer = TickBuffer(8)
    repeat(3) { buffer.row(it.toLong()) }
    assertFalse(buffer.holds(0, 2))
    assertFalse(buffer.holds(3, 2))
    assertFalse(buffer.holds(2, 5))
    assertTrue(buffer.holds(1, 3))
    assertTrue(buffer.holds(2, 2))
  }

  @Test
  fun `reset for session keeps row seq`() {
    val buffer = TickBuffer(8)
    repeat(3) { buffer.row(it.toLong()) }
    buffer.resetForSession()
    buffer.row(50)
    assertEquals(5L, buffer.currentSeq())
    assertEquals(50L, buffer.rowAt(4).tickIndex)
  }

  @Test
  fun `resize keeps rows seqs and attack index`() {
    val buffer = TickBuffer(8)
    repeat(3) { buffer.row(it.toLong()) }
    buffer.row(3, attack = true)
    repeat(3) { buffer.row((4 + it).toLong()) }

    val grown = buffer.resizedTo(12)

    assertEquals(buffer.currentSeq(), grown.currentSeq())
    assertTrue(grown.holds(1, 7))
    assertEquals((0L..6L).toList(), (1L..7L).map { grown.rowAt(it).tickIndex })
    val before = buffer.extractWindow(preWindow = 2, postWindow = 3, ticksSinceAttack = 3)
    val after = grown.extractWindow(preWindow = 2, postWindow = 3, ticksSinceAttack = 3)
    assertNotNull(before)
    assertNotNull(after)
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L), after.map { it.tickIndex })
    assertEquals(before.map { it.tickIndex }, after.map { it.tickIndex })
    assertEquals(listOf(-2, -1, 0, 1, 2), after.map { it.ticksToAttack.toInt() })
  }

  @Test
  fun `resize to smaller keeps the newest rows`() {
    val buffer = TickBuffer(10)
    repeat(8) { buffer.row(it.toLong()) }
    val shrunk = buffer.resizedTo(4)
    assertEquals(9L, shrunk.currentSeq())
    assertTrue(shrunk.holds(6, 8))
    assertFalse(shrunk.holds(5, 8))
    assertEquals(7L, shrunk.rowAt(8).tickIndex)
  }

  @Test
  fun `new rows after resize continue the sequence`() {
    val buffer = TickBuffer(4)
    repeat(3) { buffer.row(it.toLong()) }
    val grown = buffer.resizedTo(8)
    grown.row(3)
    grown.row(4)
    assertEquals(6L, grown.currentSeq())
    assertEquals((0L..4L).toList(), (1L..5L).map { grown.rowAt(it).tickIndex })
  }
}
