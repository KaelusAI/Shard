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
import ac.shard.data.TickSchema
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TickSerializer {

  const val SCHEMA_VERSION = 1
  const val CONTENT_TYPE = "application/vnd.kaelus.shard.ticks"

  const val RAW_MAGIC: Byte = 0xF5.toByte()

  const val MASKED_NCOLS = 0

  private const val HEADER_SIZE = 10 + TickSchema.MASK_BYTES

  fun serialize(
    ticks: List<TickData>,
    clientProtocol: Int,
    serverProtocol: Int,
    columnMask: LongArray = TickSchema.fullMask,
  ): ByteArray {
    val n = ticks.size
    val buf =
      ByteBuffer.allocate(HEADER_SIZE + TickSchema.rowSizeFor(columnMask) * n)
        .order(ByteOrder.LITTLE_ENDIAN)
    buf.put(RAW_MAGIC)
    buf.put(SCHEMA_VERSION.toByte())
    buf.putShort(n.toShort())
    buf.putShort(MASKED_NCOLS.toShort())
    buf.putShort(clientProtocol.toShort())
    buf.putShort(serverProtocol.toShort())
    for (word in columnMask) buf.putLong(word)
    TickSchema.packColumnsRaw(buf, ticks, columnMask)
    return buf.array()
  }
}
