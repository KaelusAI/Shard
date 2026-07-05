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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AiSerializer {
  fun serialize(features: FloatArray, count: Int): ByteArray {
    val bytes = ByteArray(count * BYTES_PER_TICK)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val floatCount = count * FEATURES_PER_TICK
    for (i in 0 until floatCount) {
      buffer.putFloat(features[i])
    }
    return bytes
  }

  private companion object {
    const val FEATURES_PER_TICK = 2
    const val BYTES_PER_FLOAT = 4
    const val BYTES_PER_TICK = FEATURES_PER_TICK * BYTES_PER_FLOAT
  }
}
