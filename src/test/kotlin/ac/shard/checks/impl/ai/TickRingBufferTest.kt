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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TickRingBufferTest {

  private val a = 'A' to floatArrayOf(1f, 101f)
  private val b = 'B' to floatArrayOf(2f, 102f)
  private val c = 'C' to floatArrayOf(3f, 103f)
  private val d = 'D' to floatArrayOf(4f, 104f)
  private val e = 'E' to floatArrayOf(5f, 105f)
  private val f = 'F' to floatArrayOf(6f, 106f)
  private val g = 'G' to floatArrayOf(7f, 107f)
  private val h = 'H' to floatArrayOf(8f, 108f)

  private fun TickRingBuffer.pushTick(tick: Pair<Char, FloatArray>) {
    push(tick.second[0], tick.second[1])
  }

  private fun TickRingBuffer.snapshotFloats(): FloatArray {
    val out = FloatArray(count * 2)
    snapshotInto(out)
    return out
  }

  private fun expected(vararg ticks: Pair<Char, FloatArray>): FloatArray =
    ticks.flatMap { it.second.toList() }.toFloatArray()

  @Test
  fun `U1 fill-exact order`() {
    val ring = TickRingBuffer(4)
    ring.pushTick(a)
    ring.pushTick(b)
    ring.pushTick(c)
    ring.pushTick(d)

    assertEquals(4, ring.count)
    assertEquals(expected(a, b, c, d).toList(), ring.snapshotFloats().toList())
  }

  @Test
  fun `U2 partial fill`() {
    val ring = TickRingBuffer(4)
    ring.pushTick(a)
    ring.pushTick(b)
    ring.pushTick(c)

    assertFalse(ring.isFull())
    assertEquals(3, ring.count)
  }

  @Test
  fun `U3 single wrap chronology`() {
    val ring = TickRingBuffer(3)
    ring.pushTick(a)
    ring.pushTick(b)
    ring.pushTick(c)
    assertEquals(expected(a, b, c).toList(), ring.snapshotFloats().toList())

    ring.pushTick(d)
    assertEquals(expected(b, c, d).toList(), ring.snapshotFloats().toList())

    ring.pushTick(e)
    assertEquals(expected(c, d, e).toList(), ring.snapshotFloats().toList())
  }

  @Test
  fun `U4 two-segment wrap physical layout`() {
    val ring = TickRingBuffer(3)
    listOf(a, b, c, d, e).forEach { ring.pushTick(it) }

    assertEquals(3, ring.count)
    assertEquals(expected(c, d, e).toList(), ring.snapshotFloats().toList())
  }

  @Test
  fun `U5 multi-wrap`() {
    val ring = TickRingBuffer(3)
    ring.pushTick(a)
    ring.pushTick(b)
    ring.pushTick(c)
    assertEquals(expected(a, b, c).toList(), ring.snapshotFloats().toList())

    ring.pushTick(d)
    assertEquals(expected(b, c, d).toList(), ring.snapshotFloats().toList())

    ring.pushTick(e)
    assertEquals(expected(c, d, e).toList(), ring.snapshotFloats().toList())
  }

  @Test
  fun `U6 reset atomicity`() {
    val ring = TickRingBuffer(4)
    ring.pushTick(a)
    ring.pushTick(b)
    ring.pushTick(c)
    assertEquals(3, ring.count)
    assertEquals(3, ring.ticksStep)

    ring.reset()
    assertEquals(0, ring.count)
    assertEquals(0, ring.ticksStep)

    ring.pushTick(e)
    ring.pushTick(f)
    ring.pushTick(g)
    ring.pushTick(h)

    assertEquals(4, ring.count)
    assertEquals(expected(e, f, g, h).toList(), ring.snapshotFloats().toList())
  }

  @Test
  fun `U7 markSent keeps window`() {
    val ring = TickRingBuffer(4)
    ring.pushTick(a)
    ring.pushTick(b)
    ring.pushTick(c)
    ring.pushTick(d)

    ring.markSent()
    assertEquals(4, ring.count)
    assertEquals(0, ring.ticksStep)

    ring.pushTick(e)
    assertEquals(expected(b, c, d, e).toList(), ring.snapshotFloats().toList())
  }

  @Test
  fun `U8 capacity is coerced to at least one`() {
    val ring = TickRingBuffer(0)

    assertEquals(1, ring.capacity)
    ring.pushTick(a)
    assertEquals(1, ring.count)
  }

  @Test
  fun `U9 index wrap does not throw`() {
    val ring = TickRingBuffer(4)
    listOf(a, b, c, d, e).forEach { ring.pushTick(it) }

    assertEquals(4, ring.count)
    assertTrue(ring.isFull())
    assertEquals(expected(b, c, d, e).toList(), ring.snapshotFloats().toList())
  }
}
