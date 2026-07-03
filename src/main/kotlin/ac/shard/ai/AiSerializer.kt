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

import ac.shard.data.TickData
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AiSerializer {
  fun serialize(ticks: Array<TickData>, count: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(count * BYTES_PER_TICK).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until count) {
      val tick = ticks[i]
      buffer.putFloat(tick.deltaYaw)
      buffer.putFloat(tick.deltaPitch)
    }
    buffer.flip()
    return buffer
  }

  private companion object {
    const val FEATURES_PER_TICK = 2
    const val BYTES_PER_FLOAT = 4
    const val BYTES_PER_TICK = FEATURES_PER_TICK * BYTES_PER_FLOAT
  }
}
