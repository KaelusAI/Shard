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
import ac.shard.http.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InferenceStreamFaultTest {

  private val profileJson =
    """
    {"profile_crc": "0000beef",
     "wire_columns": ["sequence_id", "attack_this_tick"],
     "chunk": 5, "max_chunk_rows": 205, "primary": "b",
     "models": [
       {"id": "a", "title": "A", "schedule": "slide", "window": 10, "stride": 5, "events": ["hit_player"],
        "labels": ["x"], "label_mode": "multilabel", "role": {"alert": {}, "mitigate": {}}, "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8}},
       {"id": "b", "title": "B", "schedule": "attack", "pre": 20, "post": 20, "step": 20,
        "events": ["hit_player"], "role": {"alert": {}, "mitigate": {}, "punish": {}}, "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8}},
       {"id": "c", "title": "C", "schedule": "attack", "pre": 60, "post": 30, "step": 30,
        "events": ["hit_player"], "labels": ["x", "y"], "label_mode": "multilabel", "role": {"alert": {}, "mitigate": {}, "punish": {}}, "buffer": {"flag": 50, "reset": 25, "multiplier": 100, "decrease": 1, "tracked": 8}}
     ]}
    """
  private val profile = ProfileParser.parse(Json.mapper.readTree(profileJson)).getOrThrow()

  private class Faults(
    val requestLoss: Double = 0.0,
    val responseLoss: Double = 0.0,
    val duplicate: Double = 0.0,
    val maxDelayMs: Long = 0,
    val restarts: Set<Long> = emptySet(),
  )

  private class Ring(val base: Long) {
    var hi = base - 1
    val rows = HashMap<Long, Int>()
    val pending = HashSet<Pair<Int, Long>>()
    val emitted = HashSet<Pair<Int, Long>>()
    val replay = HashMap<Pair<Int, Long>, String>()
  }

  private inner class Server {
    val rings = HashMap<Long, Ring>()

    fun restart() = rings.clear()

    fun process(chunk: ByteArray): String {
      val b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
      val flags = b.get(2).toInt()
      val key = b.getLong(3)
      val from = b.getInt(15).toLong() and 0xFFFFFFFFL
      val rows = b.getShort(27).toInt()
      val nReq = b.getShort(29).toInt()
      val ackThrough = b.getInt(19).toLong() and 0xFFFFFFFFL
      val keyHex = "%016x".format(key)
      var ring = rings[key]
      if (ring == null) {
        if (flags and FLAG_DISCONTINUITY == 0) {
          return """{"k":"$keyHex","error":{"code":"UNKNOWN_STREAM","retry":"reopen","retry_after_ms":100}}"""
        }
        ring = Ring(from).also { rings[key] = it }
      }
      for (i in 0 until nReq) {
        val idx = b.get(31 + 4 * i).toInt() and 0xFF
        val off = b.getShort(33 + 4 * i).toInt()
        ring.pending += idx to from + off
      }
      val payload = 31 + 4 * nReq
      for (i in 0 until rows) ring.rows[from + i] = b.getInt(payload + 4 * i)
      while (ring.rows.containsKey(ring.hi + 1)) ring.hi++
      val ready =
        ring.pending.filter {
          it.second <= ring.hi &&
            it !in ring.emitted &&
            profile.models[it.first].startOf(it.second) >= ring.base
        }
      for (req in ready.sortedBy { it.second }) {
        val m = profile.models[req.first]
        ring.emitted += req
        val n = if (m.singleHead) 1 else m.labels.size
        ring.replay[req] =
          (0 until n).joinToString(",") {
            "%.3f".format(java.util.Locale.ROOT, score(m.id, req.second, it))
          }
      }
      ring.pending.removeAll(ring.emitted)
      ring.replay.keys.removeIf { it.second <= ackThrough }
      val out =
        ring.replay.entries
          .sortedBy { it.key.second }
          .joinToString(",") { "[${it.key.first},${ring.hi - it.key.second},${it.value}]" }
      return """{"k":"$keyHex","h":${ring.hi},"i":[$out]}"""
    }
  }

  private fun score(model: String, end: Long, label: Int): Double =
    ((model.hashCode() * 31 + end * 7 + label) and 0x3FF) / 1024.0

  private class Pending(val at: Long, val action: () -> Unit)

  private data class Run(
    val applied: List<Pair<String, Long>>,
    val counters: StreamCounters,
    val late: Int = 0,
  )

  @Suppress("LongParameterList")
  private fun run(
    seed: Int,
    faults: Faults,
    rows: Long = 6000,
    kind: Short = 0,
    blocked: LongRange = LongRange.EMPTY,
    active: StreamProfile = profile,
    closeAt: Long = Long.MAX_VALUE,
  ): Run {
    val rng = Random(seed)
    val server = Server()
    val buf = TickBuffer(300)
    var now = 0L
    val queue = ArrayList<Pending>()
    val applied = ArrayList<Pair<String, Long>>()
    var closed = false
    var late = 0
    val service =
      object : StreamService {
        override val isEnabled = true

        override fun isBackingOff() = false

        override fun submit(chunk: ByteArray, meta: ChunkMeta): CompletableFuture<ChunkOutcome> {
          val future = CompletableFuture<ChunkOutcome>()
          meta.dispatchedAt = now
          val d1 = if (faults.maxDelayMs > 0) rng.nextLong(0, faults.maxDelayMs) else 0
          val d2 = if (faults.maxDelayMs > 0) rng.nextLong(0, faults.maxDelayMs) else 0
          val copies = if (rng.nextDouble() < faults.duplicate) 2 else 1
          if (rng.nextDouble() < faults.requestLoss) {
            queue +=
              Pending(now + TIMEOUT) {
                future.complete(ChunkOutcome.Failed(RuntimeException("lost"), true))
              }
            return future
          }
          val lostResponse = rng.nextDouble() < faults.responseLoss
          repeat(copies) { copy ->
            queue +=
              Pending(now + d1 + copy * 7) {
                val json = """{"v":2,"c":"0000beef","results":[${server.process(chunk)}]}"""
                if (copy > 0) return@Pending
                if (lostResponse) {
                  queue +=
                    Pending(now + TIMEOUT) {
                      future.complete(ChunkOutcome.Failed(RuntimeException("gone"), true))
                    }
                } else {
                  queue +=
                    Pending(now + d2) {
                      val root = StreamReplyParser.parseRoot(json, 1).getOrThrow()
                      future.complete(
                        StreamReplyParser.parseItem(
                          root.items[0],
                          root.profileCrc,
                          profile,
                          meta.to,
                        )
                      )
                    }
                }
              }
          }
          return future
        }
      }
    val stream =
      InferenceStream(
        buffer = { buf },
        profiles = { active },
        service = service,
        verdicts = { it.run() },
        sink = { _, m, end, _ ->
          applied += m.id to end
          if (closed) late++
        },
        clock = { now },
      )
    val pattern = Random(seed * 7 + 1)
    var seqId = 1
    var fighting = false
    for (t in 1..rows) {
      now = t * TICK_MS
      if (t in faults.restarts) server.restart()
      if (pattern.nextDouble() < 0.004) seqId++
      if (pattern.nextDouble() < 0.02) fighting = !fighting
      val attack = fighting && t < rows - 400 && pattern.nextDouble() < 0.35
      buf.captureRaw {
        it.sequenceId = seqId
        it.attackThisTick = attack
      }
      val gate = if (t in blocked) BlockReason.COOLDOWN else null
      if (t == closeAt) {
        stream.close()
        closed = true
      }
      stream.onRow(buf.currentSeq(), seqId, if (attack) kind else null, gate)
      buf.advance()
      do {
        val due = queue.filter { it.at <= now }.sortedBy { it.at }
        queue.removeAll(due.toSet())
        due.forEach { it.action() }
      } while (due.isNotEmpty())
    }
    return Run(applied, stream.counters, late)
  }

  @Test
  fun `a closed stream applies no verdict that arrives afterwards`() {
    repeat(5) { seed ->
      val closeAt = 2_000L + seed * 400
      val run = run(seed, Faults(maxDelayMs = 400), closeAt = closeAt)
      assertTrue(run.applied.isNotEmpty(), "the stream scored nothing before closing, seed $seed")
      assertEquals(0, run.late, "verdicts reached the player after quit, seed $seed")
    }
  }

  @Test
  fun `fault free run scores every model and never twice`() {
    repeat(5) { seed ->
      val run = run(seed, Faults())
      assertEquals(run.applied.size, run.applied.toSet().size, "duplicates in seed $seed")
      assertTrue(
        run.applied.map { it.first }.toSet() == setOf("a", "b", "c"),
        "seed $seed",
      )
      assertEquals(0L, run.counters.resends.get(), "seed $seed")
    }
  }

  @Test
  fun `a model turned off locally is never requested`() {
    val run = run(1, Faults(), active = profile.narrowed(setOf("a")))
    assertEquals(setOf("b", "c"), run.applied.map { it.first }.toSet())
  }

  @Test
  fun `loss duplication and delay are fully repaired`() {
    val faults =
      Faults(requestLoss = 0.05, responseLoss = 0.05, duplicate = 0.05, maxDelayMs = 1500)
    repeat(20) { seed ->
      val oracle = run(seed, Faults()).applied.toSet()
      val faulty = run(seed, faults)
      assertEquals(faulty.applied.size, faulty.applied.toSet().size, "duplicates in seed $seed")
      val got = faulty.applied.toSet()
      assertEquals(
        emptySet(),
        (oracle - got) + (got - oracle).map { it.copy(first = "EXTRA " + it.first) },
        "seed $seed missing/extra, lost=${faulty.counters.lostSnapshot()}",
      )
      assertTrue(faulty.counters.resends.get() > 0, "seed $seed never resent")
    }
  }

  @Test
  fun `heavy loss and delays beyond the overdue limit are still repaired`() {
    val faults = Faults(requestLoss = 0.15, responseLoss = 0.15, duplicate = 0.1, maxDelayMs = 3000)
    var missingTotal = 0
    var oracleTotal = 0
    val causes = HashMap<LossCause, Long>()
    repeat(20) { seed ->
      val oracle = run(seed + 100, Faults()).applied.toSet()
      val faulty = run(seed + 100, faults)
      val got = faulty.applied.toSet()
      val missing = oracle - got
      val counted = faulty.counters.lostSnapshot().values.sum()
      assertEquals(faulty.applied.size, got.size, "duplicates in seed $seed")
      assertEquals(emptySet(), got - oracle, "seed $seed extra")
      assertTrue(missing.size <= counted, "seed $seed lost ${missing.size} windows silently")
      missingTotal += missing.size
      oracleTotal += oracle.size
      faulty.counters.lostSnapshot().forEach { (k, v) -> causes.merge(k, v, Long::plus) }
    }
    println("heavy faults: lost $missingTotal of $oracleTotal windows, counted $causes")
    assertTrue(missingTotal * 100 < oracleTotal * 2, "lost $missingTotal of $oracleTotal")
  }

  @Test
  fun `restarts lose only counted windows and detection resumes`() {
    repeat(20) { seed ->
      val oracle = run(seed, Faults()).applied.toSet()
      val faulty = run(seed, Faults(restarts = setOf(1500L, 3500L)))
      val got = faulty.applied.toSet()
      assertEquals(faulty.applied.size, got.size, "duplicates in seed $seed")
      assertTrue(oracle.containsAll(got), "seed $seed scored windows the oracle did not")
      assertTrue(
        got.any { it.second > 3700 },
        "seed $seed never recovered after the second restart",
      )
    }
  }

  @Test
  fun `anchors of a kind the profile does not score open nothing`() {
    val run = run(1, Faults(), kind = 5)
    assertEquals(0L, run.counters.chunks.get())
    assertEquals(emptyList(), run.applied)
  }

  @Test
  fun `windows still open when a block lifts are scored`() {
    repeat(10) { seed ->
      val oracle = run(seed, Faults()).applied.toSet()
      val gap = 2000L..2100L
      val got = run(seed, Faults(), blocked = gap).applied.toSet()
      val owed = oracle.filter { it.first == "b" && it.second > gap.last + 1 }
      assertTrue(got.containsAll(owed), "seed $seed lost ${owed - got}")
      assertTrue(oracle.containsAll(got), "seed $seed scored windows the oracle did not")
    }
  }

  private companion object {
    const val TICK_MS = 50L
    const val TIMEOUT = 3000L
  }
}
