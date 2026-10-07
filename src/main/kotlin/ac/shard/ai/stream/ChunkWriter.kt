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
package ac.shard.ai.stream

import ac.shard.data.TickBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val STREAM_CONTENT_TYPE = "application/vnd.kaelus.shard.stream"
const val FLAG_DISCONTINUITY = 1

data class Request(val modelIndex: Int, val kind: Short, val endOffset: Int)

data class ChunkHeader(
  val flags: Int,
  val streamKey: Long,
  val profileCrc: Long,
  val firstRowSeq: Long,
  val ackThrough: Long,
  val skipFrom: Long,
  val rows: Int,
  val requests: List<Request>,
)

object ChunkWriter {
  const val MAGIC: Byte = 0xF5.toByte()
  const val SCHEMA: Byte = 2
  const val HEADER_SIZE = 31
  const val REQUEST_SIZE = 4

  private const val U8_MAX = 0xFF
  private const val U16_MAX = 0xFFFF
  private const val U32_MAX = 0xFFFF_FFFFL

  fun size(h: ChunkHeader, rowSize: Int): Int =
    HEADER_SIZE + REQUEST_SIZE * h.requests.size + h.rows * rowSize

  fun write(h: ChunkHeader, buf: TickBuffer, columns: List<WireColumn>): ByteArray {
    require(h.rows in 1..WireSection.MAX_CHUNK_ROWS) { "rows ${h.rows} out of range" }
    require(buf.holds(h.firstRowSeq, h.firstRowSeq + h.rows - 1)) { "chunk rows are not resident" }
    val out = header(h, size(h, columns.sumOf { it.type.size }))
    for (column in columns) {
      for (i in 0 until h.rows) column.field.writeRaw(buf.rowAt(h.firstRowSeq + i), out)
    }
    check(!out.hasRemaining()) { "chunk size mismatch" }
    return out.array()
  }

  fun probe(manifest: ByteArray = Manifest.bytes): ByteArray =
    header(
        ChunkHeader(0, 0L, 0L, 0L, 0L, 0L, 0, emptyList()),
        HEADER_SIZE + manifest.size,
      )
      .put(manifest)
      .array()

  private fun header(h: ChunkHeader, size: Int): ByteBuffer {
    require(h.flags in 0..FLAG_DISCONTINUITY) { "bad flags" }
    listOf(h.profileCrc, h.firstRowSeq, h.ackThrough, h.skipFrom).forEach {
      require(it in 0..U32_MAX) { "u32 field out of range: $it" }
    }
    require(h.requests.size <= U16_MAX) { "too many requests" }
    val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    out.put(MAGIC)
    out.put(SCHEMA)
    out.put(h.flags.toByte())
    out.putLong(h.streamKey)
    out.putInt(h.profileCrc.toInt())
    out.putInt(h.firstRowSeq.toInt())
    out.putInt(h.ackThrough.toInt())
    out.putInt(h.skipFrom.toInt())
    out.putShort(h.rows.toShort())
    out.putShort(h.requests.size.toShort())
    for (r in h.requests) {
      require(r.modelIndex in 0..U8_MAX && r.kind in 0..U8_MAX) { "bad request $r" }
      require(r.endOffset in 0 until h.rows) { "request end outside the chunk: $r" }
      out.put(r.modelIndex.toByte())
      out.put(r.kind.toByte())
      out.putShort(r.endOffset.toShort())
    }
    return out
  }
}
