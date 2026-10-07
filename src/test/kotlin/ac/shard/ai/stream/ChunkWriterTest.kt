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
import ac.shard.data.TickSchema
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ChunkWriterTest {

  private val columns =
    listOf("sequence_id", "attack_this_tick").map {
      val field = TickSchema.fieldsByName.getValue(it)
      WireColumn(it, field.wireType, field)
    }

  private fun ring(last: Long) =
    TickBuffer(8).apply {
      repeat(last.toInt()) { i ->
        val seq = i + 1L
        captureRaw {
          it.sequenceId = 3
          it.attackThisTick = seq == last
        }
        advance()
      }
    }

  private val buffer = ring(1001)

  private fun hex(s: String): ByteArray =
    s.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

  private fun header(
    flags: Int,
    from: Long,
    ack: Long,
    skip: Long,
    requests: List<Request>,
  ) = ChunkHeader(flags, 0x0123456789ABCDEFL, 0x3FA91C02L, from, ack, skip, 2, requests)

  @Test
  fun `writes vector 1`() {
    val bytes =
      ChunkWriter.write(
        header(FLAG_DISCONTINUITY, 1000, 0, 0, listOf(Request(0, 0, 1))),
        buffer,
        columns,
      )
    val expected =
      hex(
        """
        F5 02 01  EF CD AB 89 67 45 23 01  02 1C A9 3F  E8 03 00 00  00 00 00 00  00 00 00 00
        02 00  01 00  00 00 01 00  03 00 00 00 03 00 00 00  00 01
        """
      )
    assertEquals(45, bytes.size)
    assertContentEquals(expected, bytes)
  }

  @Test
  fun `writes vector 2`() {
    val bytes = ChunkWriter.write(header(0, 1200, 1063, 1100, emptyList()), ring(1201), columns)
    val expected =
      hex(
        """
        F5 02 00  EF CD AB 89 67 45 23 01  02 1C A9 3F  B0 04 00 00  27 04 00 00  4C 04 00 00
        02 00  00 00  03 00 00 00 03 00 00 00  00 01
        """
      )
    assertEquals(41, bytes.size)
    assertContentEquals(expected, bytes)
  }

  @Test
  fun `probe is a zero header followed by the manifest`() {
    val probe = ChunkWriter.probe()
    assertEquals(ChunkWriter.HEADER_SIZE + Manifest.bytes.size, probe.size)
    assertEquals(0xF5.toByte(), probe[0])
    assertEquals(2.toByte(), probe[1])
    assertEquals(0, probe.take(ChunkWriter.HEADER_SIZE).drop(2).count { it != 0.toByte() })
    assertEquals(Manifest.json, String(probe, ChunkWriter.HEADER_SIZE, Manifest.bytes.size))
  }

  @Test
  fun `refuses rows that left the ring`() {
    assertFailsWith<IllegalArgumentException> {
      ChunkWriter.write(header(0, 990, 0, 0, emptyList()), buffer, columns)
    }
  }

  @Test
  fun `refuses a request outside the chunk`() {
    assertFailsWith<IllegalArgumentException> {
      ChunkWriter.write(header(0, 1000, 0, 0, listOf(Request(0, 0, 2))), buffer, columns)
    }
  }
}
