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

import ac.shard.server.AIServer
import ac.shard.server.AIServerProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture

class DefaultAiService(
  private val transportProvider: AIServerProvider,
  private val serializer: AiSerializer,
  private val parser: AiResponseParser,
) : AiService {
  override val isEnabled: Boolean
    get() = transportProvider.get() != null

  override fun request(features: FloatArray, count: Int): CompletableFuture<AiResult> {
    val transport: AiTransport =
      transportProvider.get() ?: return CompletableFuture.completedFuture(AiResult.disabledResult())

    val payload: ByteArray = serializer.serialize(features, count)
    return transport.send(payload).thenApply(this::parse).exceptionallyCompose(this::handleError)
  }

  private fun parse(raw: String): AiResult {
    return try {
      AiResult(parser.parse(raw), raw, null, false)
    } catch (e: Exception) {
      AiResult(null, raw, e, false)
    }
  }

  private fun handleError(error: Throwable): CompletableFuture<AiResult> {
    val cause =
      if (error is java.util.concurrent.CompletionException && error.cause != null) {
        error.cause!!
      } else {
        error
      }

    if (
      cause is AIServer.RequestException && cause.code == AIServer.ResponseCode.INVALID_SEQUENCE
    ) {
      val sequence = sequenceFromDetails(cause.details) ?: parseSequence(cause.responseBody)
      val step = stepFromDetails(cause.details) ?: parseStep(cause.responseBody)
      if (sequence != null || step != null) {
        return CompletableFuture.failedFuture(AiServiceException(cause, sequence, step))
      }
    }

    return CompletableFuture.failedFuture(cause)
  }

  private fun sequenceFromDetails(details: Map<String, Any?>?): Int? =
    when (val value = details?.get("expected_sequence")) {
      is Number -> value.toInt()
      is String -> value.toIntOrNull()
      else -> null
    }

  private fun stepFromDetails(details: Map<String, Any?>?): Int? =
    when (val value = details?.get("expected_step")) {
      is Number -> value.toInt()
      is String -> value.toIntOrNull()
      else -> null
    }

  internal fun parseStep(body: String?): Int? {
    if (body.isNullOrBlank()) return null
    return runCatching { OBJECT_MAPPER.readTree(body) }
      .getOrNull()
      ?.get("details")
      ?.takeIf { it.isObject }
      ?.let { details -> parseSequenceNode(details.get("expected_step")) }
  }

  internal fun parseSequence(body: String?): Int? {
    if (body.isNullOrBlank()) return null
    return runCatching { OBJECT_MAPPER.readTree(body) }
      .getOrNull()
      ?.get("details")
      ?.takeIf { it.isObject }
      ?.let { details -> parseSequenceNode(details.get("expected_sequence")) }
  }

  private fun parseSequenceNode(sequence: JsonNode?): Int? {
    if (sequence == null) return null
    return when {
      sequence.isInt -> sequence.intValue()
      sequence.isLong -> sequence.longValue().toInt()
      sequence.isTextual -> sequence.textValue().toIntOrNull()
      else -> null
    }
  }

  companion object {
    private val OBJECT_MAPPER = ObjectMapper()
  }
}
