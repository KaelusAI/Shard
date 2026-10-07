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
import java.util.concurrent.CompletableFuture
import tools.jackson.databind.JsonNode

sealed interface InferenceOutcome {
  class Scores(val values: DoubleArray) : InferenceOutcome

  data class Error(val code: String) : InferenceOutcome
}

data class Inference(val modelIndex: Int, val end: Long, val outcome: InferenceOutcome)

data class StreamReply(
  val stream: Long,
  val profileCrc: Long,
  val haveHi: Long,
  val inferences: List<Inference>,
  val profile: StreamProfile? = null,
  val holdBelow: Long = Long.MAX_VALUE,
)

enum class Retry(val wire: String) {
  RESEND("resend"),
  DROP("drop"),
  REOPEN("reopen"),
  WAIT("wait"),
  RECONFIGURE("reconfigure");

  companion object {
    private val BY_WIRE = entries.associateBy { it.wire }

    fun of(value: String?): Retry = BY_WIRE[value] ?: WAIT
  }
}

sealed interface ChunkOutcome {
  data class Reply(val reply: StreamReply) : ChunkOutcome

  data class ItemError(val code: String, val retry: Retry, val retryAfterMs: Long) : ChunkOutcome

  data class Failed(val cause: Throwable, val wholeRequest: Boolean) : ChunkOutcome

  data class Protocol(val reason: String, val raw: String) : ChunkOutcome
}

@Suppress("LongParameterList")
class ChunkMeta(
  val key: Long,
  val keyFirstRow: Long,
  val from: Long,
  val to: Long,
  val flags: Int,
  val profileCrc: Long,
  val emittedAt: Long,
) {
  @Volatile var dispatchedAt: Long = 0L
}

const val DEFAULT_OVERDUE_MS = 2000L

interface StreamService {
  val isEnabled: Boolean

  fun isBackingOff(): Boolean

  fun overdueMs(): Long = DEFAULT_OVERDUE_MS

  fun submit(chunk: ByteArray, meta: ChunkMeta): CompletableFuture<ChunkOutcome>
}

data class ParsedRoot(val profileCrc: Long, val items: List<JsonNode>, val profile: JsonNode?)

object StreamReplyParser {
  private val MAPPER = Json.mapper
  private val KEY = Regex("[0-9a-f]{16}")
  private val CRC = Regex("[0-9a-f]{8}")
  private const val HEX_RADIX = 16
  private const val ENTRY_MIN_SIZE = 3
  const val DEFAULT_RETRY_AFTER_MS = 1000L

  fun parseRoot(json: String, batchSize: Int): Result<ParsedRoot> = runCatching {
    val root = MAPPER.readTree(json)
    require(root.path("v").asInt(-1) == 2) { "reply version is not 2" }
    val crc = root.path("c").asString("")
    require(CRC.matches(crc)) { "reply profile crc is malformed" }
    val results = root.path("results")
    require(results.isArray && results.size() == batchSize) {
      "reply holds ${results.size()} results for $batchSize items"
    }
    ParsedRoot(
      crc.toLong(HEX_RADIX),
      results.toList(),
      root.get("profile")?.takeIf { it.isObject },
    )
  }

  fun parseItem(
    node: JsonNode,
    rootCrc: Long,
    profile: StreamProfile?,
    lastRow: Long,
  ): ChunkOutcome {
    val error = node.get("error")
    if (error != null && error.isObject) {
      return ChunkOutcome.ItemError(
        error.path("code").asString("UNKNOWN"),
        Retry.of(error.path("retry").takeIf { it.isTextual }?.asString("")),
        error
          .path("retry_after_ms")
          .takeIf { it.canConvertToLong() && it.asLong(0L) >= 0 }
          ?.asLong(0L) ?: DEFAULT_RETRY_AFTER_MS,
      )
    }
    val key = node.path("k").asString("")
    val have = node.get("h")
    return when {
      !KEY.matches(key) -> ChunkOutcome.Protocol("item key is malformed", node.toString())
      have != null && (!have.canConvertToLong() || have.asLong(0L) < 0) ->
        ChunkOutcome.Protocol("item have_hi is malformed", node.toString())
      else -> {
        val haveHi = have?.asLong(0L) ?: lastRow
        val (inferences, holdBelow) = entries(node.path("i"), haveHi, profile)
        ChunkOutcome.Reply(
          StreamReply(
            java.lang.Long.parseUnsignedLong(key, HEX_RADIX),
            rootCrc,
            haveHi,
            inferences,
            profile,
            holdBelow,
          )
        )
      }
    }
  }

  private fun entries(
    node: JsonNode,
    haveHi: Long,
    profile: StreamProfile?,
  ): Pair<List<Inference>, Long> {
    val inferences = ArrayList<Inference>()
    var holdBelow = if (profile == null) 0L else Long.MAX_VALUE
    if (!node.isArray) return inferences to holdBelow
    for (item in node) {
      when (val parsed = entry(item, haveHi, profile)) {
        is Inference -> inferences += parsed
        is Long -> holdBelow = minOf(holdBelow, parsed)
      }
    }
    return inferences to holdBelow
  }

  @Suppress("ReturnCount")
  private fun entry(node: JsonNode, haveHi: Long, profile: StreamProfile?): Any? {
    if (!node.isArray) return 0L
    val size =
      if (node.size() > 0 && node[node.size() - 1].isObject) node.size() - 1 else node.size()
    if (size < ENTRY_MIN_SIZE) return 0L
    val index = node[0].takeIf { it.canConvertToInt() }?.asInt(0) ?: return 0L
    val back = node[1].takeIf { it.canConvertToLong() }?.asLong(0L) ?: return 0L
    if (back < 0 || back > haveHi) return 0L
    val end = haveHi - back
    if (profile == null) return end
    if (index !in profile.models.indices) return end
    val model = profile.servable(index) ?: return null
    return outcome(node, size, model)?.let { Inference(index, end, it) } ?: end
  }

  private fun outcome(node: JsonNode, size: Int, model: ModelSpec): InferenceOutcome? {
    if (size == ENTRY_MIN_SIZE && node[2].isTextual)
      return InferenceOutcome.Error(node[2].asString(""))
    val scores = (2 until size).map { node[it] }
    val expected = if (model.singleHead) 1 else model.labels.size
    val valid = scores.size == expected && scores.all { it.isNumber && it.asDouble(0.0).isFinite() }
    return if (valid) InferenceOutcome.Scores(scores.map { it.asDouble(0.0) }.toDoubleArray())
    else null
  }
}
