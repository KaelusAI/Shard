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
package ac.shard.server

import ac.shard.server.AIServer.RequestException
import ac.shard.server.AIServer.ResponseCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

object ShardError {
  private val MAPPER = ObjectMapper()

  private data class Meta(val code: ResponseCode, val retryable: Boolean, val backoff: Boolean)

  private val TAXONOMY: Map<String, Meta> = buildTaxonomy()

  fun fromServerCode(serverCode: String?): ResponseCode =
    TAXONOMY[serverCode]?.code ?: ResponseCode.UNKNOWN_ERROR

  fun parse(status: Int, body: String?): RequestException {
    val errorNode = errorObject(body)
    return if (errorNode != null && errorNode.get("code")?.isTextual == true) {
      fromError(errorNode, status, body)
    } else {
      fromStatusFallback(status, body)
    }
  }

  fun fromError(errorNode: JsonNode, fallbackStatus: Int, body: String? = null): RequestException {
    val serverCode = errorNode.get("code")?.takeIf { it.isTextual }?.asText()
    val status = errorNode.get("status")?.takeIf { it.isInt }?.asInt() ?: fallbackStatus
    val message = errorNode.get("message")?.takeIf { it.isTextual }?.asText()
    val serverRetryable = errorNode.get("retryable")?.takeIf { it.isBoolean }?.asBoolean() ?: false
    val meta = TAXONOMY[serverCode]
    val resolved = meta?.code ?: ResponseCode.fromStatusCode(status)
    return RequestException(
      code = resolved,
      message = message ?: "HTTP $status",
      serverCode = serverCode,
      serverMessage = message,
      details = parseDetails(errorNode.get("details")),
      httpStatus = status,
      retryable = meta?.retryable ?: serverRetryable,
      backoff = meta?.backoff ?: policyFor(resolved).second,
      responseBody = body ?: errorNode.toString(),
    )
  }

  private fun fromStatusFallback(status: Int, body: String?): RequestException {
    val gateway = status in HTTP_BAD_GATEWAY..HTTP_GATEWAY_TIMEOUT
    val code = ResponseCode.fromStatusCode(status)
    val (retry, backoff) = policyFor(code)
    return RequestException(
      code = code,
      message = "HTTP $status: ${body?.take(MAX_BODY_SNIPPET).orEmpty()}",
      serverCode = if (gateway) "GATEWAY_ERROR" else "HTTP_$status",
      httpStatus = status,
      retryable = retry,
      backoff = backoff,
      responseBody = body,
    )
  }

  private fun policyFor(code: ResponseCode): Pair<Boolean, Boolean> =
    when (code) {
      ResponseCode.RATE_LIMITED,
      ResponseCode.SERVER_ERROR,
      ResponseCode.SERVICE_UNAVAILABLE -> true to true
      ResponseCode.UNAUTHORIZED,
      ResponseCode.INSUFFICIENT_CREDITS -> false to true
      else -> false to false
    }

  private fun errorObject(body: String?): JsonNode? {
    if (body.isNullOrBlank()) return null
    return runCatching { MAPPER.readTree(body) }.getOrNull()?.get("error")?.takeIf { it.isObject }
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseDetails(node: JsonNode?): Map<String, Any?>? {
    if (node == null || !node.isObject) return null
    return runCatching { MAPPER.convertValue(node, Map::class.java) as Map<String, Any?> }
      .getOrNull()
  }

  private fun buildTaxonomy(): Map<String, Meta> = buildMap {
    val auth = Meta(ResponseCode.UNAUTHORIZED, retryable = false, backoff = true)
    for (code in
      listOf(
        "MISSING_KEY",
        "INVALID_KEY",
        "SERVER_INACTIVE",
        "IP_NOT_WHITELISTED",
        "VERIFICATION_REQUIRED",
        "AUTH_RATE_LIMITED",
      )) {
      put(code, auth)
    }
    val credits = Meta(ResponseCode.INSUFFICIENT_CREDITS, retryable = false, backoff = true)
    put("INSUFFICIENT_CREDITS", credits)
    put("CREDITS_UNAVAILABLE", credits)
    put("RATE_LIMITED", Meta(ResponseCode.RATE_LIMITED, retryable = true, backoff = true))
    put(
      "RECONFIGURE_REQUIRED",
      Meta(ResponseCode.RECONFIGURE_REQUIRED, retryable = false, backoff = false),
    )
    val badRequest = Meta(ResponseCode.BAD_REQUEST, retryable = false, backoff = false)
    for (code in
      listOf(
        "INVALID_INPUT",
        "UNSUPPORTED_MEDIA_TYPE",
        "EMPTY_BATCH",
        "MALFORMED_BATCH",
        "BATCH_TOO_MANY_ITEMS",
      )) {
      put(code, badRequest)
    }
    val tooLarge = Meta(ResponseCode.PAYLOAD_TOO_LARGE, retryable = false, backoff = false)
    put("ITEM_TOO_LARGE", tooLarge)
    put("BATCH_TOO_LARGE", tooLarge)
    val serverError = Meta(ResponseCode.SERVER_ERROR, retryable = true, backoff = true)
    put("INTERNAL", serverError)
    put("GATEWAY_ERROR", serverError)
    put(
      "SERVICE_UNAVAILABLE",
      Meta(ResponseCode.SERVICE_UNAVAILABLE, retryable = true, backoff = true),
    )
  }

  private const val HTTP_BAD_GATEWAY = 502
  private const val HTTP_GATEWAY_TIMEOUT = 504
  private const val MAX_BODY_SNIPPET = 200
}
