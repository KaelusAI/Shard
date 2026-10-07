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

import ac.shard.server.AIServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import tools.jackson.databind.JsonNode

fun interface StreamSender {
  fun send(items: List<ByteArray>, profileCrc: String?): CompletableFuture<String>
}

@Suppress("LongParameterList", "TooManyFunctions")
class StreamTransport(
  private val sender: StreamSender,
  private val profiles: () -> StreamProfile?,
  private val profileFor: (Long) -> StreamProfile?,
  private val onProfile: (JsonNode, Long?) -> Unit,
  private val onManifestRequired: () -> Unit,
  private val backingOff: () -> Boolean,
  private val clock: () -> Long,
  private val maxBatch: Int = DEFAULT_MAX_BATCH,
  private val errors: StreamErrorLog = StreamErrorLog({ _, _ -> }, clock),
  private val sendOn: Executor = Executor(Runnable::run),
) : StreamService {
  private class Item(
    val bytes: ByteArray,
    val meta: ChunkMeta,
    val future: CompletableFuture<ChunkOutcome>,
  )

  private val queue = ConcurrentLinkedQueue<Item>()
  private val size = AtomicInteger()
  private val queuedBytes = AtomicLong()
  private val requestSlots = Semaphore(MAX_CONCURRENT_REQUESTS)

  val stats = StreamStats()

  val queued: Int
    get() = size.get()

  val concurrentRequests: Int
    get() = MAX_CONCURRENT_REQUESTS - requestSlots.availablePermits()

  @Volatile private var stopped = false

  @Volatile private var rttMs = 0.0

  override val isEnabled: Boolean
    get() = !stopped

  fun now(): Long = clock()

  override fun isBackingOff(): Boolean = backingOff() || queuedBytes.get() >= MAX_QUEUED_BYTES

  override fun overdueMs(): Long =
    if (rttMs <= 0.0) DEFAULT_OVERDUE_MS
    else (rttMs * OVERDUE_RTT_FACTOR).toLong().coerceIn(MIN_OVERDUE_MS, MAX_OVERDUE_MS)

  override fun submit(chunk: ByteArray, meta: ChunkMeta): CompletableFuture<ChunkOutcome> {
    val future = CompletableFuture<ChunkOutcome>()
    if (stopped) {
      future.complete(stoppedOutcome())
      return future
    }
    queue += Item(chunk, meta, future)
    queuedBytes.addAndGet(chunk.size.toLong())
    val batchReady = size.incrementAndGet() >= maxBatch
    when {
      stopped -> failQueued()
      batchReady -> sendOn.execute(::drain)
    }
    return future
  }

  fun drain() {
    while (queue.isNotEmpty() && requestSlots.tryAcquire()) {
      val limit = if (size.get() > maxBatch) AIServer.BATCH_MAX_ITEMS else maxBatch
      val items = ArrayList<Item>(limit)
      while (items.size < limit) {
        val item = queue.poll() ?: break
        size.decrementAndGet()
        queuedBytes.addAndGet(-item.bytes.size.toLong())
        items += item
      }
      if (items.isEmpty()) {
        requestSlots.release()
        return
      }
      stats.onBatch(items.size, concurrentRequests, size.get(), queuedBytes.get())
      val profile = profiles()
      dispatch(items, profile?.configHeader, profile?.crc ?: 0L) {
        requestSlots.release()
        if (queue.isNotEmpty()) sendOn.execute(::drain)
      }
    }
  }

  fun probe(bytes: ByteArray): CompletableFuture<ChunkOutcome> {
    val future = CompletableFuture<ChunkOutcome>()
    if (stopped) {
      future.complete(stoppedOutcome())
      return future
    }
    dispatch(listOf(Item(bytes, ChunkMeta(0L, 0L, 0L, 0L, 0, 0L, 0L), future)), null, null)
    return future
  }

  @Suppress("TooGenericExceptionCaught")
  private fun dispatch(
    items: List<Item>,
    header: String?,
    sentCrc: Long?,
    onDone: () -> Unit = {},
  ) {
    val now = clock()
    items.forEach { it.meta.dispatchedAt = now }
    val sent =
      try {
        sender.send(items.map { it.bytes }, header)
      } catch (e: Exception) {
        CompletableFuture.failedFuture(e)
      }
    sent.orTimeout(REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS).whenComplete { body, error ->
      try {
        if (error != null) {
          stats.onFailure()
          fail(items, unwrap(error), sentCrc)
        } else {
          val rtt = clock() - now
          stats.onReply(rtt)
          noteRtt(rtt)
          deliver(items, body, sentCrc)
        }
      } finally {
        onDone()
      }
    }
  }

  private fun noteRtt(sample: Long) {
    val previous = rttMs
    rttMs = if (previous <= 0.0) sample.toDouble() else previous + (sample - previous) * RTT_WEIGHT
  }

  private fun deliver(items: List<Item>, body: String, sentCrc: Long?) {
    val root = StreamReplyParser.parseRoot(body, items.size)
    root.onFailure { cause ->
      errors.onBadReply(cause.message ?: "bad reply")
      items.forEach {
        it.future.complete(ChunkOutcome.Protocol(cause.message ?: "bad reply", body))
      }
    }
    root.onSuccess { parsed ->
      errors.onRequestSucceeded()
      parsed.profile?.let { onProfile(it, sentCrc) }
      val profile = profileFor(parsed.profileCrc)
      items.forEachIndexed { i, item ->
        val node = parsed.items[i]
        val outcome =
          if (keyMismatch(node, item.meta.key)) {
            ChunkOutcome.Protocol("reply key does not match the chunk", node.toString())
          } else {
            StreamReplyParser.parseItem(node, parsed.profileCrc, profile, item.meta.to)
          }
        report(outcome, profile)
        item.future.complete(outcome)
      }
    }
  }

  private fun keyMismatch(node: JsonNode, key: Long): Boolean {
    val echoed = node.path("k").asString("")
    return key != 0L &&
      echoed.isNotEmpty() &&
      echoed != java.lang.Long.toUnsignedString(key, HEX_RADIX).padStart(KEY_HEX_DIGITS, '0')
  }

  private fun report(outcome: ChunkOutcome, profile: StreamProfile?) {
    when (outcome) {
      is ChunkOutcome.ItemError -> errors.onItemError(outcome.code)
      is ChunkOutcome.Protocol -> errors.onBadReply(outcome.reason)
      is ChunkOutcome.Reply ->
        for (inference in outcome.reply.inferences) {
          val error = inference.outcome as? InferenceOutcome.Error ?: continue
          val model =
            profile?.models?.getOrNull(inference.modelIndex)?.id ?: "#${inference.modelIndex}"
          errors.onEntryError(model, error.code)
        }
      is ChunkOutcome.Failed -> Unit
    }
  }

  private fun fail(items: List<Item>, cause: Throwable, sentCrc: Long?) {
    val request = cause as? AIServer.RequestException
    val profile =
      request
        ?.responseBody
        ?.let { runCatching { MAPPER.readTree(it) }.getOrNull() }
        ?.get("profile")
        ?.takeIf { it.isObject }
    profile?.let { onProfile(it, sentCrc) }
    val needsProbe =
      request?.serverCode == MANIFEST_REQUIRED ||
        (request?.retry == Retry.RECONFIGURE && profile == null)
    if (needsProbe) onManifestRequired()
    if (profile == null && !needsProbe) errors.onRequestFailed(cause)
    items.forEach { it.future.complete(ChunkOutcome.Failed(cause, true)) }
  }

  private fun unwrap(error: Throwable): Throwable =
    if (error is CompletionException && error.cause != null) error.cause!! else error

  fun stop() {
    stopped = true
    failQueued()
  }

  private fun failQueued() {
    while (true) {
      val item = queue.poll() ?: break
      size.decrementAndGet()
      queuedBytes.addAndGet(-item.bytes.size.toLong())
      item.future.complete(stoppedOutcome())
    }
  }

  private fun stoppedOutcome(): ChunkOutcome =
    ChunkOutcome.Failed(IllegalStateException("transport stopped"), true)

  companion object {
    const val DEFAULT_MAX_BATCH = 32
    const val MAX_CONCURRENT_REQUESTS = 16
    const val MAX_QUEUED_BYTES = 32L * 1024 * 1024
    const val MANIFEST_REQUIRED = "MANIFEST_REQUIRED"
    private const val REQUEST_DEADLINE_MS = 30_000L
    private const val HEX_RADIX = 16
    private const val KEY_HEX_DIGITS = 16
    private const val RTT_WEIGHT = 0.125
    private const val OVERDUE_RTT_FACTOR = 4.0
    private const val MIN_OVERDUE_MS = 1_000L
    private const val MAX_OVERDUE_MS = 10_000L
    private val MAPPER = ac.shard.http.Json.mapper
  }
}
