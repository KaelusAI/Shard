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

import ac.shard.http.Json
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class StreamReplyParserTest {

  private val mapper = Json.mapper

  private val profile =
    ProfileParser.parse(
        mapper.readTree(
          """
          {"profile_crc": "0000beef",
           "wire_columns": ["sequence_id", "attack_this_tick"],
           "chunk": 5, "max_chunk_rows": 65, "primary": "b",
           "models": [
             {"id": "b", "title": "B", "schedule": "attack", "pre": 20, "post": 20, "step": 20,
              "events": ["hit_player"], "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8}},
             {"id": "c", "title": "C", "schedule": "attack", "pre": 10, "post": 10, "step": 10,
              "events": ["hit_player"], "labels": ["x", "y"], "label_mode": "multilabel",
              "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8}},
             {"id": "d", "title": "D", "schedule": "periodic", "events": ["hit_player"]}
           ]}
          """
        )
      )
      .getOrThrow()

  private fun item(json: String, with: StreamProfile?, lastRow: Long = 100) =
    assertIs<ChunkOutcome.Reply>(
        StreamReplyParser.parseItem(mapper.readTree(json), 0xbeef, with, lastRow)
      )
      .reply

  private fun error(json: String) =
    assertIs<ChunkOutcome.ItemError>(
      StreamReplyParser.parseItem(mapper.readTree(json), 0xbeef, profile, 100)
    )

  @Test
  fun `entries map by the profile the reply names and a clean reply acks in full`() {
    val r = item("""{"k":"0000000000000001","h":100,"i":[[0,5,0.97],[1,0,0.2,0.95]]}""", profile)
    assertEquals(2, r.inferences.size)
    assertEquals(profile, r.profile)
    assertEquals(Long.MAX_VALUE, r.holdBelow)
  }

  @Test
  fun `an entry that does not parse holds the ack below its end`() {
    val r = item("""{"k":"0000000000000001","h":100,"i":[[0,5,0.97],[1,10,0.2]]}""", profile)
    assertEquals(1, r.inferences.size, "the two-label model sent one score")
    assertEquals(90L, r.holdBelow, "the server must keep replaying the window ending at 90")
  }

  @Test
  fun `a reply under a profile the client does not know acks nothing`() {
    val r = item("""{"k":"0000000000000001","h":100,"i":[[0,5,0.97]]}""", null)
    assertEquals(0, r.inferences.size)
    assertEquals(0L, r.holdBelow)
  }

  @Test
  fun `a missing h means the last row of the chunk`() {
    val r = item("""{"k":"0000000000000001","i":[[0,5,0.97]]}""", profile, lastRow = 140)
    assertEquals(140L, r.haveHi)
    assertEquals(135L, r.inferences.single().end)
  }

  @Test
  fun `a missing i is an empty entry list`() {
    val r = item("""{"k":"0000000000000001","h":100}""", profile)
    assertEquals(0, r.inferences.size)
    assertEquals(Long.MAX_VALUE, r.holdBelow)
  }

  @Test
  fun `a trailing object in an entry is ignored`() {
    val r = item("""{"k":"0000000000000001","h":100,"i":[[1,0,0.2,0.95,{"later":1}]]}""", profile)
    assertEquals(1, r.inferences.size)
    assertEquals(Long.MAX_VALUE, r.holdBelow)
  }

  @Test
  fun `entries of an inert model are dropped without holding the ack`() {
    val r = item("""{"k":"0000000000000001","h":100,"i":[[2,0,0.99],[0,0,0.5]]}""", profile)
    assertEquals(listOf(0), r.inferences.map { it.modelIndex })
    assertEquals(Long.MAX_VALUE, r.holdBelow)
  }

  @Test
  fun `an item error carries retry and pause`() {
    val e = error("""{"error":{"code":"STREAM_LIMIT","retry":"wait","retry_after_ms":2500}}""")
    assertEquals(Retry.WAIT, e.retry)
    assertEquals(2500L, e.retryAfterMs)
    val unknown = error("""{"error":{"code":"SOMETHING_NEW","retry":"later"}}""")
    assertEquals(Retry.WAIT, unknown.retry)
    assertEquals(StreamReplyParser.DEFAULT_RETRY_AFTER_MS, unknown.retryAfterMs)
    assertEquals(Retry.REOPEN, error("""{"error":{"code":"X","retry":"reopen"}}""").retry)
  }
}
