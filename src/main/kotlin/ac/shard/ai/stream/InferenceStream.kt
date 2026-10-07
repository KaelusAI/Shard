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
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

enum class StreamState {
  IDLE,
  STREAMING,
  BLOCKED,
}

enum class BlockReason {
  NO_PROFILE,
  DISABLED,
  VEHICLE,
  REGION,
  COOLDOWN,
  STREAM_LIMIT,
  PROTOCOL,
}

enum class LossCause {
  RING_LOST,
  SEQUENCE_BREAK,
  PROFILE_CHANGE,
  BLOCKED,
  RESIDENCY,
  QUIT,
}

object StreamKeys {
  private val random = SecureRandom()

  fun next(): Long {
    while (true) {
      val key = random.nextLong()
      if (key != 0L) return key
    }
  }
}

class StreamCounters {
  val chunks = AtomicLong()
  val rowsSent = AtomicLong()
  val resends = AtomicLong()
  val keysOpened = AtomicLong()
  val continuations = AtomicLong()
  val verdicts = AtomicLong()
  val entryErrors = AtomicLong()
  private val lost = ConcurrentHashMap<LossCause, AtomicLong>()

  fun lose(cause: LossCause, windows: Int) {
    if (windows > 0) lost.computeIfAbsent(cause) { AtomicLong() }.addAndGet(windows.toLong())
  }

  fun lost(cause: LossCause): Long = lost[cause]?.get() ?: 0L

  fun lostSnapshot(): Map<LossCause, Long> = lost.mapValues { it.value.get() }
}

data class StreamSettings(
  val overdueMs: Long = 0,
  val ttlMarginMs: Long = 5000,
)

fun interface VerdictSink {
  fun onVerdict(profile: StreamProfile, model: ModelSpec, end: Long, scores: DoubleArray)
}

@Suppress("TooManyFunctions", "LongParameterList")
class InferenceStream(
  private val buffer: () -> TickBuffer,
  private val profiles: () -> StreamProfile?,
  private val service: StreamService,
  private val verdicts: Executor,
  private val sink: VerdictSink,
  private val clock: () -> Long,
  private val settings: StreamSettings = StreamSettings(),
  private val keys: () -> Long = StreamKeys::next,
) {
  private class Key(
    val id: Long,
    val firstRow: Long,
    val scoreFrom: Long,
    var profile: StreamProfile,
  ) {
    var sentThrough = firstRow - 1
    var lastSendAt = 0L
    val requests = TreeMap<Long, MutableList<Attempt>>()
    val applied: MutableSet<Pair<String, Long>> = ConcurrentHashMap.newKeySet()
    val inflight: MutableSet<ChunkMeta> = ConcurrentHashMap.newKeySet()

    @Volatile var haveHi = firstRow - 1

    @Volatile var lostAt = 0L

    @Volatile var reopenNotBefore = 0L

    @Volatile var confirmedAt = Long.MAX_VALUE

    fun owed(): Int =
      requests.values.sumOf { list -> list.count { (it.model to it.end) !in applied } }
  }

  val counters = StreamCounters()

  @Volatile
  var state: StreamState = StreamState.BLOCKED
    private set

  @Volatile
  var blockReason: BlockReason? = BlockReason.NO_PROFILE
    private set

  @Volatile private var closed = false

  @Volatile private var blockedUntil = 0L

  @Volatile private var blockedUntilReason: BlockReason? = null

  private var chain: SelectionChain? = null
  private var current: Key? = null
  private val retired = ArrayList<Key>()
  private var segmentSeqId = Int.MIN_VALUE
  private var segmentStart = 1L
  private var lastAnchor = 0L
  private var lastProfile: StreamProfile? = null

  fun onRow(
    t: Long,
    sequenceId: Int,
    anchorKind: Short?,
    gate: BlockReason?,
    idle: Boolean = false,
  ) {
    if (closed) return
    val profile = profiles()
    val attempts = profile?.let { admit(it, t, sequenceId, anchorKind, idle) }
    val now = clock()
    val reason = gate ?: blockedUntilReason?.takeIf { now < blockedUntil }
    when {
      profile == null -> block(BlockReason.NO_PROFILE, LossCause.PROFILE_CHANGE)
      reason != null -> block(reason, LossCause.BLOCKED)
      else -> advance(profile, t, attempts, now)
    }
  }

  private fun admit(
    profile: StreamProfile,
    t: Long,
    sequenceId: Int,
    anchorKind: Short?,
    idle: Boolean,
  ): List<Attempt>? {
    adopt(profile)
    if (sequenceId != segmentSeqId) {
      drop(LossCause.SEQUENCE_BREAK)
      segmentSeqId = sequenceId
      segmentStart = t
    }
    val chain = chain!!
    chain.pruneBelow(t - profile.closeHorizon)
    val periodic = if (idle) emptyList() else chain.onTick(t)
    if (periodic.isNotEmpty()) lastAnchor = t
    val kind = anchorKind?.takeIf { it in profile.anchorKinds } ?: return periodic.ifEmpty { null }
    lastAnchor = t
    return chain.onAnchor(t, kind) + periodic
  }

  private fun advance(
    profile: StreamProfile,
    t: Long,
    attempts: List<Attempt>?,
    now: Long,
  ) {
    if (state == StreamState.BLOCKED) state = StreamState.IDLE
    blockReason = null
    current?.let { if (it.lostAt != 0L && now >= it.reopenNotBefore) drop(LossCause.RING_LOST) }
    val openable = current?.lostAt?.let { it == 0L } != false
    val owed = attempts != null || chain!!.hasAttemptEndingAtLeast(segmentStart, t)
    val opened = state == StreamState.IDLE && owed && openable
    if (opened) open(profile, t, now)
    repairRetired(profile, now)
    val live = current?.takeIf { it.lostAt == 0L } ?: return
    val offered = if (opened) chain!!.attemptsEndingIn(t, Long.MAX_VALUE) else attempts
    offered?.let { offer(live, it) }
    prune(live)
    if (state == StreamState.STREAMING) {
      step(profile, live, t, now)
    } else {
      repairClosed(profile, live, now)
    }
  }

  private fun prune(key: Key) {
    val have = key.haveHi
    key.requests.headMap(have, true).clear()
    key.applied.removeIf { it.second < have - APPLIED_WINDOW }
  }

  private fun repairClosed(profile: StreamProfile, key: Key, now: Long) {
    if (!resendClosed(profile, key, now)) current = null
  }

  private fun resendClosed(profile: StreamProfile, key: Key, now: Long): Boolean {
    val covered = coveredThrough(key, now)
    val cause =
      when {
        key.lostAt != 0L -> LossCause.RING_LOST
        covered >= key.sentThrough -> null
        now - key.lastSendAt >= profile.ringTtlMs -> LossCause.RING_LOST
        key.sentThrough - covered > key.profile.wire.maxChunkRows -> LossCause.RESIDENCY
        !buffer().holds(covered + 1, key.sentThrough) -> LossCause.RESIDENCY
        else -> {
          val flags = if (covered + 1 == key.firstRow) FLAG_DISCONTINUITY else 0
          emit(key.profile, key, covered + 1, key.sentThrough, flags, now)
          counters.resends.incrementAndGet()
          null
        }
      }
    if (cause != null) counters.lose(cause, key.owed())
    return cause == null
  }

  private fun repairRetired(profile: StreamProfile, now: Long) {
    retired.removeIf { key ->
      val alive = resendClosed(profile, key, now)
      !alive || (key.inflight.isEmpty() && key.haveHi >= key.sentThrough)
    }
  }

  private fun retire(key: Key) {
    if (key.lostAt != 0L) {
      counters.lose(LossCause.RING_LOST, key.owed())
      return
    }
    if (key.haveHi >= key.sentThrough && key.inflight.isEmpty()) return
    retired += key
    while (retired.size > MAX_RETIRED) counters.lose(
      LossCause.RESIDENCY,
      retired.removeAt(0).owed(),
    )
  }

  private fun adopt(profile: StreamProfile) {
    if (profile === lastProfile) return
    val previous = current?.profile
    chain = chain?.adopting(profile) ?: SelectionChain(profile.active)
    if (previous != null && previous.wire != profile.wire) drop(LossCause.PROFILE_CHANGE)
    (retired + listOfNotNull(current))
      .filter { it.profile.wire == profile.wire }
      .forEach { key ->
        key.profile = profile
        key.requests.values.forEach { list -> list.removeAll { profile.model(it.model) == null } }
      }
    lastProfile = profile
  }

  private fun block(reason: BlockReason, cause: LossCause) {
    if (state == StreamState.STREAMING) drop(cause)
    state = StreamState.BLOCKED
    blockReason = reason
  }

  private fun drop(cause: LossCause) {
    val key = current ?: return
    if (cause == LossCause.SEQUENCE_BREAK) retire(key) else counters.lose(cause, key.owed())
    current = null
    if (state == StreamState.STREAMING) state = StreamState.IDLE
  }

  private fun open(profile: StreamProfile, t: Long, now: Long) {
    val buf = buffer()
    val wire = profile.wire
    val key = current
    val continuable =
      key != null &&
        key.lostAt == 0L &&
        key.profile.wire == wire &&
        now - key.lastSendAt < profile.ringTtlMs - settings.ttlMarginMs &&
        t - wire.maxPre <= key.sentThrough + 1 &&
        buf.holds(key.sentThrough + 1, t)
    if (continuable) {
      counters.continuations.incrementAndGet()
    } else {
      val from =
        maxOf(t - wire.maxPre, segmentStart, buf.oldestSeq(), t - wire.maxChunkRows + 1, 1L)
      current?.let(::retire)
      current = Key(keys(), from, t, profile)
      counters.keysOpened.incrementAndGet()
    }
    state = StreamState.STREAMING
  }

  private fun offer(key: Key, attempts: List<Attempt>) {
    for (a in attempts) {
      val m = key.profile.model(a.model) ?: continue
      val start = m.startOf(a.end)
      if (a.end >= key.scoreFrom && start >= key.firstRow && start >= segmentStart) {
        val list = key.requests.getOrPut(a.end) { mutableListOf() }
        if (a !in list) list += a
      }
    }
  }

  private fun coveredThrough(key: Key, now: Long): Long {
    var covered = key.haveHi
    val overdue = settings.overdueMs.takeIf { it > 0 } ?: service.overdueMs()
    val live =
      key.inflight
        .filter { it.dispatchedAt == 0L || now - it.dispatchedAt < overdue }
        .sortedBy { it.from }
    for (meta in live) if (meta.from <= covered + 1) covered = maxOf(covered, meta.to)
    return covered
  }

  private fun step(profile: StreamProfile, key: Key, t: Long, now: Long) {
    val covered = coveredThrough(key, now)
    val resend = covered < key.sentThrough
    val from = if (resend) covered + 1 else key.sentThrough + 1
    val closeThrough = lastAnchor + profile.closeHorizon - 1
    val due = from <= t && (resend || cutDue(profile, key, t, closeThrough))
    val resident = buffer().holds(from, t) && t - from + 1 <= profile.wire.maxChunkRows
    when {
      !due -> Unit
      !resident -> {
        counters.lose(LossCause.RESIDENCY, key.owed())
        current = null
        state = StreamState.IDLE
      }
      else -> {
        val closing = t >= closeThrough
        val flags = if (from == key.firstRow) FLAG_DISCONTINUITY else 0
        emit(profile, key, from, t, flags, now)
        if (resend) counters.resends.incrementAndGet()
        key.sentThrough = maxOf(key.sentThrough, t)
        key.lastSendAt = now
        if (closing) state = StreamState.IDLE
      }
    }
  }

  private fun cutDue(profile: StreamProfile, key: Key, t: Long, closeThrough: Long): Boolean =
    key.sentThrough < key.firstRow ||
      key.requests.subMap(key.sentThrough + 1, true, t, true).isNotEmpty() ||
      t - key.sentThrough >= profile.wire.chunk ||
      t >= closeThrough

  private fun emit(
    profile: StreamProfile,
    key: Key,
    from: Long,
    to: Long,
    flags: Int,
    now: Long,
  ) {
    val requests =
      key.requests
        .subMap(from, true, to, true)
        .values
        .flatten()
        .filter { (it.model to it.end) !in key.applied }
        .mapNotNull { a ->
          val index = profile.indexOf(a.model).takeIf { it >= 0 } ?: return@mapNotNull null
          Request(index, a.kind, (a.end - from).toInt())
        }
        .sortedWith(compareBy({ it.endOffset }, { it.modelIndex }))
    val header =
      ChunkHeader(
        flags,
        key.id,
        profile.crc,
        from,
        key.haveHi.coerceAtLeast(0L),
        0L,
        (to - from + 1).toInt(),
        requests,
      )
    val bytes = ChunkWriter.write(header, buffer(), profile.wire.columns)
    val meta = ChunkMeta(key.id, key.firstRow, from, to, flags, profile.crc, now)
    key.inflight += meta
    counters.chunks.incrementAndGet()
    counters.rowsSent.addAndGet(to - from + 1)
    service.submit(bytes, meta).thenAcceptAsync({ onOutcome(key, profile, meta, it) }, verdicts)
  }

  private fun onOutcome(key: Key, profile: StreamProfile, meta: ChunkMeta, outcome: ChunkOutcome) {
    key.inflight -= meta
    when (outcome) {
      is ChunkOutcome.Reply -> onReply(key, profile, outcome.reply)
      is ChunkOutcome.ItemError -> onItemError(key, meta, outcome)
      is ChunkOutcome.Protocol -> blockFor(BlockReason.PROTOCOL, clock() + PROTOCOL_PAUSE_MS)
      is ChunkOutcome.Failed -> Unit
    }
  }

  private fun onReply(key: Key, sentUnder: StreamProfile, reply: StreamReply) {
    if (closed || reply.stream != key.id) return
    val profile = reply.profile ?: sentUnder.takeIf { it.crc == reply.profileCrc } ?: return
    for (inference in reply.inferences) {
      val model = profile.servable(inference.modelIndex) ?: continue
      when (val outcome = inference.outcome) {
        is InferenceOutcome.Error -> counters.entryErrors.incrementAndGet()
        is InferenceOutcome.Scores ->
          if (key.applied.add(model.id to inference.end)) {
            counters.verdicts.incrementAndGet()
            sink.onVerdict(profile, model, inference.end, outcome.values)
          }
      }
    }
    val acked = minOf(reply.haveHi, reply.holdBelow - 1)
    if (acked > key.haveHi) key.haveHi = acked
    if (key.haveHi >= key.firstRow && key.confirmedAt == Long.MAX_VALUE) key.confirmedAt = clock()
  }

  private fun onItemError(key: Key, meta: ChunkMeta, error: ChunkOutcome.ItemError) {
    val now = clock()
    val ours = meta.key == key.id
    when (error.retry) {
      Retry.RESEND,
      Retry.RECONFIGURE -> Unit
      Retry.DROP ->
        if (ours) {
          key.reopenNotBefore = now
          key.lostAt = now
        }
      Retry.REOPEN ->
        if (ours && meta.emittedAt > key.confirmedAt) {
          key.reopenNotBefore = now + error.retryAfterMs
          key.lostAt = now
        }
      Retry.WAIT ->
        blockFor(
          if (error.code == STREAM_LIMIT) BlockReason.STREAM_LIMIT else BlockReason.COOLDOWN,
          now + error.retryAfterMs,
        )
    }
  }

  private fun blockFor(reason: BlockReason, until: Long) {
    blockedUntilReason = reason
    blockedUntil = until
  }

  fun progress(modelId: String, t: Long): IntArray? = chain?.progress(modelId, t)

  fun close() {
    closed = true
    drop(LossCause.QUIT)
    state = StreamState.BLOCKED
  }

  private companion object {
    const val APPLIED_WINDOW = 1024L
    const val PROTOCOL_PAUSE_MS = 60_000L
    const val MAX_RETIRED = 3
    const val STREAM_LIMIT = "STREAM_LIMIT"
  }
}
