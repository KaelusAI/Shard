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

import ac.shard.Shard
import ac.shard.ai.AiBatchTransport
import ac.shard.ai.AiTransport
import ac.shard.ai.TickSerializer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Suppress("LongParameterList")
class AIServer(
  private val plugin: Shard,
  url: String,
  private val apiKey: String,
  private val apiCooldown: ApiCooldown,
  private val instanceId: String,
  private val gzipEnabled: Boolean,
  private val configFingerprint: () -> String = { "" },
) : AiTransport, AiBatchTransport {
  private val serverUri: URI = URI.create(url)
  private val userAgent: String = "Shard/" + plugin.description.version

  override fun send(payload: ByteArray): CompletableFuture<String> {
    if (apiCooldown.isWaiting()) {
      return CompletableFuture.failedFuture(
        RequestException(ResponseCode.WAITING, "Server is in backoff.")
      )
    }

    return sendRequest(payload, batch = false)
  }

  override fun sendBatch(items: List<ByteArray>): CompletableFuture<String> {
    val rejection =
      when {
        apiCooldown.isWaiting() -> RequestException(ResponseCode.WAITING, "Server is in backoff.")
        items.isEmpty() -> RequestException(ResponseCode.BAD_REQUEST, "Empty batch")
        else -> null
      }
    if (rejection != null) return CompletableFuture.failedFuture(rejection)
    return sendRequest(encodeBatchFraming(items), batch = true)
  }

  private fun sendRequest(body: ByteArray, batch: Boolean): CompletableFuture<String> {
    val wireBody = if (gzipEnabled) gzip(body) else body
    val builder =
      HttpRequest.newBuilder(serverUri)
        .header("Content-Type", TickSerializer.CONTENT_TYPE)
        .header("User-Agent", userAgent)
        .header("X-API-Key", apiKey)
        .header("X-Instance-Id", instanceId)
        .header("X-Model-Config", configFingerprint())
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .POST(HttpRequest.BodyPublishers.ofByteArray(wireBody))
        .timeout(if (batch) BATCH_REQUEST_TIMEOUT else REQUEST_TIMEOUT)
    if (gzipEnabled) {
      builder.header("Content-Encoding", "gzip")
    }
    if (batch) {
      builder.header("X-Batch", "1")
    }

    return HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
      .thenApply { response -> catchResponse(response) }
      .exceptionallyCompose { throwable -> catchException(throwable) }
  }

  private fun gzip(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(data.size / GZIP_SIZE_ESTIMATE_DIVISOR + 1)
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
  }

  private fun encodeBatchFraming(items: List<ByteArray>): ByteArray {
    check(items.size <= BATCH_MAX_ITEMS) {
      "Batch count ${items.size} exceeds wire-format max $BATCH_MAX_ITEMS"
    }
    val totalSize = BATCH_COUNT_SIZE + items.sumOf { BATCH_ITEM_HEADER_SIZE + it.size }
    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(items.size.toShort())
    for (item in items) {
      buf.putInt(item.size)
      buf.put(item)
    }
    return buf.array()
  }

  private fun catchResponse(response: HttpResponse<ByteArray>): String {
    val statusCode = response.statusCode()
    val body = decodeBody(response)
    if (statusCode !in HTTP_OK_MIN..HTTP_OK_MAX) {
      val error = ShardError.parse(statusCode, body)
      if (error.backoff) {
        apiCooldown.recordFailure()
      }
      throw error
    }

    apiCooldown.recordSuccess()
    return body
  }

  @Suppress("ReturnCount")
  private fun decodeBody(response: HttpResponse<ByteArray>): String {
    val raw = response.body() ?: return ""
    val encoded = response.headers().firstValue("Content-Encoding").orElse("").equals("gzip", true)
    if (!encoded || raw.isEmpty()) return String(raw, Charsets.UTF_8)
    return runCatching { GZIPInputStream(raw.inputStream()).use { it.readBytes() } }
      .map { String(it, Charsets.UTF_8) }
      .getOrElse { String(raw, Charsets.UTF_8) }
  }

  private fun <U> catchException(throwable: Throwable): CompletableFuture<U> {
    val cause =
      if (throwable is java.util.concurrent.CompletionException && throwable.cause != null) {
        throwable.cause!!
      } else {
        throwable
      }
    if (cause is RequestException) {
      return CompletableFuture.failedFuture(cause)
    }

    val isTimeout = cause is HttpTimeoutException
    if (!isTimeout) {
      apiCooldown.recordFailure()
    }

    val code = if (isTimeout) ResponseCode.TIMEOUT else ResponseCode.NETWORK_ERROR

    return CompletableFuture.failedFuture(
      RequestException(
        code = code,
        message = "Request failed: " + cause.message,
        serverCode = code.name,
        httpStatus = code.httpCode,
        retryable = true,
        backoff = !isTimeout,
        cause = cause,
      )
    )
  }

  enum class ResponseCode(val httpCode: Int) {
    SUCCESS(200),
    BAD_REQUEST(400),
    INSUFFICIENT_CREDITS(HTTP_PAYMENT_REQUIRED),
    UNAUTHORIZED(403),
    NOT_FOUND(404),
    PAYLOAD_TOO_LARGE(413),
    RECONFIGURE_REQUIRED(422),
    RATE_LIMITED(429),
    SERVER_ERROR(500),
    SERVICE_UNAVAILABLE(503),
    TIMEOUT(-1),
    NETWORK_ERROR(-2),
    PARSE_ERROR(-3),
    WAITING(-5),
    UNKNOWN_ERROR(-4);

    companion object {
      @JvmStatic
      fun fromStatusCode(code: Int): ResponseCode {
        entries
          .firstOrNull { it.httpCode == code }
          ?.let {
            return it
          }
        return when {
          code == HTTP_UNAUTHORIZED -> UNAUTHORIZED
          code >= HTTP_SERVER_ERROR_MIN -> SERVER_ERROR
          code >= HTTP_CLIENT_ERROR_MIN -> BAD_REQUEST
          else -> UNKNOWN_ERROR
        }
      }
    }
  }

  @Suppress("LongParameterList")
  class RequestException(
    val code: ResponseCode,
    message: String,
    val serverCode: String? = null,
    val serverMessage: String? = null,
    val details: Map<String, Any?>? = null,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    val backoff: Boolean = false,
    val responseBody: String? = null,
    cause: Throwable? = null,
  ) : RuntimeException(message, cause)

  companion object {
    private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
    private val REQUEST_TIMEOUT = Duration.ofSeconds(5)
    private val BATCH_REQUEST_TIMEOUT = Duration.ofSeconds(10)

    private const val BATCH_COUNT_SIZE = 2
    private const val BATCH_ITEM_HEADER_SIZE = 4
    const val BATCH_MAX_ITEMS = 256
    private const val GZIP_SIZE_ESTIMATE_DIVISOR = 4

    const val HTTP_PAYMENT_REQUIRED = 402
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_CLIENT_ERROR_MIN = 400
    private const val HTTP_SERVER_ERROR_MIN = 500

    private val HTTP_CLIENT: HttpClient =
      HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    fun shutdownHttpClient() {
      runCatching { (HTTP_CLIENT as? AutoCloseable)?.close() }
    }
  }
}
