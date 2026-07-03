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
package ac.shard.ai

import ac.shard.data.TickData
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteOrder
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AiSerializerTest {

  private val serializer = AiSerializer()

  private fun mockTick(dYaw: Float, dPitch: Float): TickData = mockk {
    every { deltaYaw } returns dYaw
    every { deltaPitch } returns dPitch
  }

  @Test
  fun `serialize single tick writes two little-endian floats`() {
    val buffer =
      serializer.serialize(arrayOf(mockTick(1.5f, -2.0f)), 1).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(8, buffer.remaining())
    assertEquals(1.5f, buffer.getFloat(0))
    assertEquals(-2.0f, buffer.getFloat(4))
  }

  @Test
  fun `serialize multiple ticks preserves order`() {
    val ticks = arrayOf(mockTick(1.0f, 2.0f), mockTick(3.0f, 4.0f), mockTick(5.0f, 6.0f))
    val buffer = serializer.serialize(ticks, 3).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(24, buffer.remaining())
    val expected = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
    for (i in expected.indices) {
      assertEquals(expected[i], buffer.getFloat(i * 4))
    }
  }

  @Test
  fun `serialize uses count not array length`() {
    val ticks = arrayOf(mockTick(10.0f, 0f), mockTick(20.0f, 0f), mockTick(30.0f, 0f))
    val buffer = serializer.serialize(ticks, 2).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(16, buffer.remaining())
    assertEquals(10.0f, buffer.getFloat(0))
    assertEquals(20.0f, buffer.getFloat(8))
  }

  @Test
  fun `serialize is reusable across calls`() {
    val buf1 = serializer.serialize(arrayOf(mockTick(100.0f, 0f)), 1).order(ByteOrder.LITTLE_ENDIAN)
    val buf2 = serializer.serialize(arrayOf(mockTick(200.0f, 0f)), 1).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(100.0f, buf1.getFloat(0))
    assertEquals(200.0f, buf2.getFloat(0))
  }
}
