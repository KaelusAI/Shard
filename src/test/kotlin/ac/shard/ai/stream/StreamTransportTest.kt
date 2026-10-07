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

import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class StreamTransportTest {

  private fun meta(key: Long) = ChunkMeta(key, 1, 1, 1, 0, 0, 0)

  private class Recorder(var reply: (Int) -> CompletableFuture<String>) : StreamSender {
    val batches = mutableListOf<Pair<Int, String?>>()

    override fun send(items: List<ByteArray>, profileCrc: String?): CompletableFuture<String> {
      batches += items.size to profileCrc
      return reply(items.size)
    }
  }

  private val sentUnder = mutableListOf<Long?>()
  private var manifestAsks = 0

  private fun transport(
    sender: StreamSender,
    profiles: MutableList<JsonNode> = mutableListOf(),
    max: Int = 32,
  ) =
    StreamTransport(
      sender,
      { null },
      { null },
      { node, crc ->
        profiles.add(node)
        sentUnder.add(crc)
      },
      { manifestAsks++ },
      { false },
      { 0L },
      max,
    )

  @Test
  fun `a batch reply is split per item in order`() {
    val body =
      """{"v":2,"c":"0000beef","results":[{"k":"0000000000000001","h":5,"i":[]},""" +
        """{"k":"0000000000000002","error":{"code":"UNKNOWN_STREAM","retry":"reopen","retry_after_ms":7}}]}"""
    val sender = Recorder { CompletableFuture.completedFuture(body) }
    val t = transport(sender)
    val a = t.submit(byteArrayOf(1), meta(1))
    val b = t.submit(byteArrayOf(2), meta(2))
    t.drain()
    val first = assertIs<ChunkOutcome.Reply>(a.join())
    assertEquals(1L, first.reply.stream)
    assertEquals(5L, first.reply.haveHi)
    val second = assertIs<ChunkOutcome.ItemError>(b.join())
    assertEquals("UNKNOWN_STREAM", second.code)
    assertEquals(Retry.REOPEN, second.retry)
    assertEquals(7L, second.retryAfterMs)
    assertEquals(
      listOf<Pair<Int, String?>>(2 to null),
      sender.batches,
      "no profile, no X-Model-Config",
    )
  }

  @Test
  fun `a full queue drains at once`() {
    val sender = Recorder { CompletableFuture<String>() }
    val t = transport(sender, max = 3)
    repeat(3) { t.submit(byteArrayOf(0), meta(it.toLong())) }
    assertEquals(listOf<Pair<Int, String?>>(3 to null), sender.batches)
  }

  @Test
  fun `no more than the concurrent request limit hang at once`() {
    val sender = Recorder { CompletableFuture<String>() }
    val t = transport(sender, max = 1)
    repeat(StreamTransport.MAX_CONCURRENT_REQUESTS * 3) {
      t.submit(byteArrayOf(0), meta(it.toLong()))
    }
    t.drain()
    assertEquals(StreamTransport.MAX_CONCURRENT_REQUESTS, sender.batches.size)
  }

  @Test
  fun `a backlog leaves in larger batches once a slot frees, nothing is dropped`() {
    val pending = mutableListOf<CompletableFuture<String>>()
    val sender = Recorder { CompletableFuture<String>().also(pending::add) }
    val t = transport(sender, max = 1)
    val backlog = 100
    repeat(StreamTransport.MAX_CONCURRENT_REQUESTS + backlog) {
      t.submit(byteArrayOf(0), meta(it.toLong()))
    }

    pending.first().completeExceptionally(RuntimeException("down"))

    assertEquals(StreamTransport.MAX_CONCURRENT_REQUESTS + 1, sender.batches.size)
    assertEquals(backlog, sender.batches.last().first)
  }

  @Test
  fun `an oversized queue pauses the stream instead of growing`() {
    val sender = Recorder { CompletableFuture<String>() }
    val t = transport(sender, max = Int.MAX_VALUE)
    assertFalse(t.isBackingOff())
    t.submit(ByteArray(StreamTransport.MAX_QUEUED_BYTES.toInt()), meta(1))
    assertTrue(t.isBackingOff())
  }

  @Test
  fun `a failed request fails every item without throwing`() {
    val sender = Recorder { CompletableFuture.failedFuture(RuntimeException("down")) }
    val t = transport(sender)
    val a = t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertTrue(assertIs<ChunkOutcome.Failed>(a.join()).wholeRequest)
  }

  @Test
  fun `a sender that throws frees its slot and fails its items`() {
    val sender = StreamSender { _, _ ->
      throw java.io.UncheckedIOException(java.io.IOException("x"))
    }
    val t = transport(sender)
    val a = t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertTrue(assertIs<ChunkOutcome.Failed>(a.join()).wholeRequest)
    assertEquals(0, t.concurrentRequests)
  }

  @Test
  fun `a malformed reply is a protocol outcome and a root profile is handed over`() {
    val profiles = mutableListOf<JsonNode>()
    val sender = Recorder { CompletableFuture.completedFuture("""{"v":1}""") }
    val t = transport(sender, profiles)
    val a = t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertIs<ChunkOutcome.Protocol>(a.join())
    sender.reply = {
      CompletableFuture.completedFuture(
        """{"v":2,"c":"0000beef","results":[{"k":"0000000000000001","h":1}],"profile":{"profile_crc":"0000beef"}}"""
      )
    }
    t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertEquals(1, profiles.size)
    assertEquals(
      listOf<Long?>(0L),
      sentUnder,
      "the CRC the request went out under, not the reply's",
    )
  }

  @Test
  fun `stop fails what is queued`() {
    val t = transport(Recorder { CompletableFuture<String>() })
    val a = t.submit(byteArrayOf(1), meta(1))
    t.stop()
    assertIs<ChunkOutcome.Failed>(a.join())
    assertIs<ChunkOutcome.Failed>(t.submit(byteArrayOf(1), meta(1)).join())
  }

  @Test
  fun `a whole request refusal asks for the manifest and hands over a root profile`() {
    val profiles = mutableListOf<JsonNode>()
    val body =
      """{"error":{"code":"RECONFIGURE_REQUIRED","retry":"reconfigure"},"profile":{"profile_crc":"0000beef"}}"""
    val refusal =
      ac.shard.server.AIServer.RequestException(
        ac.shard.server.AIServer.ResponseCode.RECONFIGURE_REQUIRED,
        "422",
        serverCode = "RECONFIGURE_REQUIRED",
        responseBody = body,
      )
    val t = transport(Recorder { CompletableFuture.failedFuture(refusal) }, profiles)
    t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertEquals(1, profiles.size)
    assertEquals(0, manifestAsks)
    val missing =
      ac.shard.server.AIServer.RequestException(
        ac.shard.server.AIServer.ResponseCode.UNKNOWN_ERROR,
        "422",
        serverCode = StreamTransport.MANIFEST_REQUIRED,
      )
    val u = transport(Recorder { CompletableFuture.failedFuture(missing) })
    u.submit(byteArrayOf(1), meta(1))
    u.drain()
    assertEquals(1, manifestAsks)
  }

  @Test
  fun `a probe goes alone and without a profile header`() {
    val sender = Recorder { CompletableFuture<String>() }
    val t = transport(sender)
    t.submit(byteArrayOf(9), meta(1))
    t.probe(byteArrayOf(1))
    assertEquals(listOf<Pair<Int, String?>>(1 to null), sender.batches)
  }

  @Test
  fun `a reconfigure refusal without a profile asks for a probe`() {
    val refusal =
      ac.shard.server.AIServer.RequestException(
        ac.shard.server.AIServer.ResponseCode.RECONFIGURE_REQUIRED,
        "422",
        serverCode = "RECONFIGURE_REQUIRED",
        retry = Retry.RECONFIGURE,
        responseBody = """{"error":{"code":"RECONFIGURE_REQUIRED","retry":"reconfigure"}}""",
      )
    val t = transport(Recorder { CompletableFuture.failedFuture(refusal) })
    t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertEquals(1, manifestAsks)
  }

  @Test
  fun `a sender that throws fails the batch instead of the caller`() {
    val t = transport(StreamSender { _, _ -> throw IllegalArgumentException("bad header") })
    val a = t.submit(byteArrayOf(1), meta(1))
    t.drain()
    assertIs<ChunkOutcome.Failed>(a.join())
    assertIs<ChunkOutcome.Failed>(t.probe(byteArrayOf(1)).join())
  }

  @Test
  fun `entry errors and unreadable items reach the error log`() {
    val lines = mutableListOf<String>()
    val body =
      """{"v":2,"c":"0000beef","results":[{"k":"0000000000000001","h":5,"i":[[0,0,"NON_FINITE_INPUT"]]},""" +
        """{"k":"zz"}]}"""
    val t =
      StreamTransport(
        Recorder { CompletableFuture.completedFuture(body) },
        { null },
        { null },
        { _, _ -> },
        {},
        { false },
        { 0L },
        32,
        StreamErrorLog({ _, message -> lines += message }, { 0L }),
      )
    t.submit(byteArrayOf(1), meta(1))
    t.submit(byteArrayOf(2), meta(2))
    t.drain()
    assertTrue(lines.any { "does not match" in it }, "unreadable item: $lines")
  }

  @Test
  fun `chunks carry the profile crc and a probe never does`() {
    val profile =
      ProfileParser.parse(
          ac.shard.http.Json.mapper.readTree(
            """{"profile_crc":"0000beef","wire_columns":["sequence_id","attack_this_tick"],
                 "chunk":5,"max_chunk_rows":64,"primary":"b",
                 "models":[{"id":"b","schedule":"attack","pre":20,"post":20,"step":20,"events": ["hit_player"],
                 "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8}}]}"""
          )
        )
        .getOrThrow()
    val sender = Recorder { CompletableFuture<String>() }
    val t = StreamTransport(sender, { profile }, { null }, { _, _ -> }, {}, { false }, { 0L })
    t.submit(byteArrayOf(9), meta(1))
    t.drain()
    t.probe(byteArrayOf(1))
    assertEquals(listOf<Pair<Int, String?>>(1 to "0000beef", 1 to null), sender.batches)
  }

  @Test
  fun `a reply whose key does not match the chunk is not applied`() {
    val lines = mutableListOf<String>()
    val body =
      """{"v":2,"c":"0000beef","results":[{"k":"0000000000000002","h":5,"i":[]},""" +
        """{"k":"0000000000000001","error":{"code":"UNKNOWN_STREAM","retry":"reopen"}}]}"""
    val t =
      StreamTransport(
        Recorder { CompletableFuture.completedFuture(body) },
        { null },
        { null },
        { _, _ -> },
        {},
        { false },
        { 0L },
        32,
        StreamErrorLog({ _, message -> lines += message }, { 0L }),
      )
    val a = t.submit(byteArrayOf(1), meta(1))
    val b = t.submit(byteArrayOf(2), meta(2))
    t.drain()
    assertIs<ChunkOutcome.Protocol>(a.join())
    assertIs<ChunkOutcome.Protocol>(b.join())
    assertTrue(lines.any { "does not match" in it })
  }
}
