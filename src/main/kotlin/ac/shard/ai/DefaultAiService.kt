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
  private val parser: AiResponseParser,
) : AiService {
  override val isEnabled: Boolean
    get() = transportProvider.get() != null

  override fun request(payload: ByteArray): CompletableFuture<AiResult> {
    val transport: AiTransport =
      transportProvider.get() ?: return CompletableFuture.completedFuture(AiResult.disabledResult())

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
      cause is AIServer.RequestException && cause.code == AIServer.ResponseCode.RECONFIGURE_REQUIRED
    ) {
      val preWindow =
        intFromDetails(cause.details, "expected_pre_window")
          ?: parseIntFromBody(cause.responseBody, "expected_pre_window")
      val postWindow =
        intFromDetails(cause.details, "expected_post_window")
          ?: parseIntFromBody(cause.responseBody, "expected_post_window")
      val step =
        intFromDetails(cause.details, "expected_step")
          ?: parseIntFromBody(cause.responseBody, "expected_step")
      val columns =
        stringListFromDetails(cause.details, "expected_columns")
          ?: parseStringListFromBody(cause.responseBody, "expected_columns")
      val negotiated = AiServiceException(cause, preWindow, postWindow, step, columns)
      if (negotiated.hasNewParams) {
        return CompletableFuture.failedFuture(negotiated)
      }
    }

    return CompletableFuture.failedFuture(cause)
  }

  private fun intFromDetails(details: Map<String, Any?>?, key: String): Int? =
    when (val value = details?.get(key)) {
      is Number -> value.toInt()
      is String -> value.toIntOrNull()
      else -> null
    }

  private fun stringListFromDetails(details: Map<String, Any?>?, key: String): List<String>? =
    (details?.get(key) as? Collection<*>)
      ?.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
      ?.takeIf { it.isNotEmpty() }

  internal fun parseStringListFromBody(body: String?, key: String): List<String>? {
    if (body.isNullOrBlank()) return null
    return runCatching { OBJECT_MAPPER.readTree(body) }
      .getOrNull()
      ?.get("details")
      ?.takeIf { it.isObject }
      ?.get(key)
      ?.takeIf { it.isArray }
      ?.mapNotNull { it.takeIf(JsonNode::isTextual)?.textValue()?.takeIf(String::isNotBlank) }
      ?.takeIf { it.isNotEmpty() }
  }

  internal fun parseIntFromBody(body: String?, key: String): Int? {
    if (body.isNullOrBlank()) return null
    return runCatching { OBJECT_MAPPER.readTree(body) }
      .getOrNull()
      ?.get("details")
      ?.takeIf { it.isObject }
      ?.let { details -> parseIntNode(details.get(key)) }
  }

  private fun parseIntNode(node: JsonNode?): Int? {
    if (node == null) return null
    return when {
      node.isInt -> node.intValue()
      node.isLong -> node.longValue().toInt()
      node.isTextual -> node.textValue().toIntOrNull()
      else -> null
    }
  }

  companion object {
    private val OBJECT_MAPPER = ObjectMapper()
  }
}
