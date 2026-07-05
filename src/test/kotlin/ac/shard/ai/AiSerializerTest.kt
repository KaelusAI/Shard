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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AiSerializerTest {

  private val serializer = AiSerializer()

  @Test
  fun `serialize single tick writes two little-endian floats`() {
    val bytes = serializer.serialize(floatArrayOf(1.5f, -2.0f), 1)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(8, bytes.size)
    assertEquals(1.5f, buffer.getFloat(0))
    assertEquals(-2.0f, buffer.getFloat(4))
  }

  @Test
  fun `serialize multiple ticks preserves order`() {
    val features = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
    val bytes = serializer.serialize(features, 3)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(24, bytes.size)
    for (i in features.indices) {
      assertEquals(features[i], buffer.getFloat(i * 4))
    }
  }

  @Test
  fun `serialize uses count not array length`() {
    val features = floatArrayOf(10.0f, 0f, 20.0f, 0f, 30.0f, 0f)
    val bytes = serializer.serialize(features, 2)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(16, bytes.size)
    assertEquals(10.0f, buffer.getFloat(0))
    assertEquals(20.0f, buffer.getFloat(8))
  }

  @Test
  fun `serialize is reusable across calls`() {
    val bytes1 = serializer.serialize(floatArrayOf(100.0f, 0f), 1)
    val bytes2 = serializer.serialize(floatArrayOf(200.0f, 0f), 1)
    val buf1 = ByteBuffer.wrap(bytes1).order(ByteOrder.LITTLE_ENDIAN)
    val buf2 = ByteBuffer.wrap(bytes2).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(100.0f, buf1.getFloat(0))
    assertEquals(200.0f, buf2.getFloat(0))
  }
}
