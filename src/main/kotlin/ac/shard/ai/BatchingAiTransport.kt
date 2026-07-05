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

import ac.shard.platform.scheduler.TaskHandle
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServer
import ac.shard.server.ShardError
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

private const val TICKS_PER_SECOND = 20L
private const val MILLIS_PER_TICK = 1000L / TICKS_PER_SECOND

class BatchingAiTransport(
  private val batchTransport: AiBatchTransport,
  private val singleTransport: AiTransport,
  private val scheduler: SchedulerService,
  private val logger: Logger,
  private val config: BatchConfig,
) : AiTransport {
  private val queue = ConcurrentLinkedQueue<PendingItem>()
  private val queueSize = AtomicInteger(0)

  @Volatile private var timerTask: TaskHandle? = null
  @Volatile private var stopped = false

  override fun send(payload: ByteArray): CompletableFuture<String> {
    val future = CompletableFuture<String>()
    if (stopped) {
      future.completeExceptionally(InterruptedException("BatchingAiTransport stopped"))
      return future
    }
    queue.add(PendingItem(payload, future))
    val newSize = queueSize.incrementAndGet()

    when {
      stopped -> failPending(InterruptedException("BatchingAiTransport stopped"))
      newSize >= config.maxBatchSize -> scheduler.runAsync(::drainAndSend)
    }
    return future
  }

  @Synchronized
  fun start() {
    if (timerTask != null) return
    val period = config.maxDelayMs.coerceAtLeast(MILLIS_PER_TICK)
    timerTask = scheduler.runTimerAsync(::drainAndSend, period, period)
  }

  @Synchronized
  fun stop() {
    stopped = true
    timerTask?.cancel()
    timerTask = null
    failPending(InterruptedException("BatchingAiTransport shutdown"))
  }

  private fun drainAndSend() {
    if (queueSize.get() == 0) return
    val drained = drainUpTo(config.maxBatchSize)
    if (drained.isEmpty()) return
    sendBatch(drained)
  }

  private fun drainUpTo(limit: Int): List<PendingItem> {
    val drained = ArrayList<PendingItem>(limit.coerceAtMost(queueSize.get()))
    repeat(limit) {
      val item = queue.poll() ?: return@repeat
      drained.add(item)
      queueSize.decrementAndGet()
    }
    return drained
  }

  private fun sendBatch(items: List<PendingItem>) {
    val payloads = items.map { it.payload }
    batchTransport.sendBatch(payloads).whenComplete { responseBody, throwable ->
      if (throwable != null) {
        val cause = unwrap(throwable)
        items.forEach { it.future.completeExceptionally(cause) }
        return@whenComplete
      }
      dispatchBatchResults(items, responseBody)
    }
  }

  private fun dispatchBatchResults(items: List<PendingItem>, responseBody: String) {
    val results = tryParseBatch(responseBody, items) ?: return
    if (results.size != items.size) {
      val mismatch =
        AIServer.RequestException(
          AIServer.ResponseCode.PARSE_ERROR,
          "Batch result count ${results.size} mismatched item count ${items.size}",
        )
      items.forEach { it.future.completeExceptionally(mismatch) }
      return
    }
    items.forEachIndexed { index, item -> completeItem(item, results[index]) }
  }

  private fun tryParseBatch(
    responseBody: String,
    items: List<PendingItem>,
  ): List<BatchItemResult>? {
    return try {
      parseBatchResults(responseBody)
    } catch (e: com.fasterxml.jackson.core.JacksonException) {
      logger.warning("[AiCheck] Failed to parse batch response: ${e.message}")
      items.forEach { it.future.completeExceptionally(e) }
      null
    } catch (e: IllegalStateException) {
      logger.warning("[AiCheck] Invalid batch response shape: ${e.message}")
      items.forEach { it.future.completeExceptionally(e) }
      null
    }
  }

  private fun completeItem(item: PendingItem, result: BatchItemResult) {
    val probability = result.probability
    if (probability != null) {
      item.future.complete(probabilityResponseJson(probability))
      return
    }
    val errorNode = result.error
    if (errorNode == null) {
      item.future.completeExceptionally(
        AIServer.RequestException(AIServer.ResponseCode.PARSE_ERROR, "Batch item missing result")
      )
      return
    }
    val error = ShardError.fromError(errorNode, HTTP_OK)
    if (
      error.code == AIServer.ResponseCode.INVALID_SEQUENCE &&
        error.details?.get("expected_sequence") == null
    ) {
      recoverInvalidSequence(item)
    } else {
      item.future.completeExceptionally(error)
    }
  }

  private fun recoverInvalidSequence(item: PendingItem) {
    singleTransport.send(item.payload).whenComplete { body, error ->
      if (body != null) {
        item.future.complete(body)
      } else {
        item.future.completeExceptionally(unwrap(error))
      }
    }
  }

  private fun parseBatchResults(body: String): List<BatchItemResult> {
    val root: JsonNode = OBJECT_MAPPER.readTree(body)
    val results = root.get("results") ?: error("Missing 'results' field in batch response")
    if (!results.isArray) error("'results' is not an array")
    return results.map { node ->
      BatchItemResult(
        probability = node.get("probability")?.takeIf { it.isNumber }?.asDouble(),
        error = node.get("error")?.takeIf { it.isObject },
      )
    }
  }

  private fun probabilityResponseJson(probability: Double): String =
    """{"probability":$probability}"""

  private fun failPending(reason: Throwable) {
    while (true) {
      val item = queue.poll() ?: break
      queueSize.decrementAndGet()
      item.future.completeExceptionally(reason)
    }
  }

  private fun unwrap(throwable: Throwable?): Throwable {
    if (throwable == null) return RuntimeException("Unknown failure")
    return if (throwable is java.util.concurrent.CompletionException && throwable.cause != null) {
      throwable.cause!!
    } else {
      throwable
    }
  }

  data class BatchConfig(val maxBatchSize: Int, val maxDelayMs: Long)

  private data class PendingItem(val payload: ByteArray, val future: CompletableFuture<String>)

  private data class BatchItemResult(val probability: Double?, val error: JsonNode?)

  companion object {
    private const val HTTP_OK = 200
    private val OBJECT_MAPPER = ObjectMapper()
  }
}
