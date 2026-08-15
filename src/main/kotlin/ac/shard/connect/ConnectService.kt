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
package ac.shard.connect

import ac.shard.Shard
import ac.shard.config.ConfigManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

internal const val MAX_RESPONSE_BYTES = 1024 * 1024
private const val READ_CHUNK_BYTES = 8 * 1024

internal fun readCapped(stream: InputStream): String {
  val out = ByteArrayOutputStream()
  val chunk = ByteArray(READ_CHUNK_BYTES)
  while (out.size() < MAX_RESPONSE_BYTES) {
    val read = stream.read(chunk, 0, minOf(chunk.size, MAX_RESPONSE_BYTES - out.size()))
    if (read < 0) break
    out.write(chunk, 0, read)
  }
  return out.toString(Charsets.UTF_8)
}

internal fun isSecurePanelUrl(url: String): Boolean =
  try {
    val uri = URI.create(url.trim())
    when (uri.scheme?.lowercase()) {
      "https" -> true
      "http" -> uri.host?.lowercase()?.removeSurrounding("[", "]") in LOOPBACK_HOSTS
      else -> false
    }
  } catch (_: IllegalArgumentException) {
    false
  }

sealed interface StartResult {
  data class Started(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
  ) : StartResult

  data class Error(val message: String) : StartResult
}

sealed interface PollResult {
  data object Pending : PollResult

  data class SlowDown(val intervalSeconds: Long) : PollResult

  data class Approved(
    val secretKey: String,
    val serverId: String?,
    val serverName: String?,
    val allowlistedIp: String?,
    val inferenceUrl: String?,
  ) : PollResult

  data object Denied : PollResult

  data object Expired : PollResult

  data class Error(val message: String) : PollResult
}

sealed interface RevokeResult {
  data object Revoked : RevokeResult

  data class Error(val message: String) : RevokeResult
}

sealed interface LinkResult {
  data class Linked(
    val secretKey: String,
    val serverId: String?,
    val serverName: String?,
    val allowlistedIp: String?,
    val inferenceUrl: String?,
  ) : LinkResult

  data object InvalidOrExpired : LinkResult

  data class Error(val message: String) : LinkResult
}

enum class LinkIntent(val wire: String) {
  CONNECT("connect"),
  SETUP("setup"),
}

@Suppress("TooGenericExceptionCaught", "ReturnCount")
class ConnectService(private val plugin: Shard, private val configManager: ConfigManager) {
  private val mapper = ObjectMapper()
  private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

  fun start(
    instanceId: String? = null,
    intent: LinkIntent = LinkIntent.CONNECT,
  ): StartResult {
    return try {
      val (code, node) =
        post(
          "/api/v1/device/start",
          mapOf(
            "client_id" to CLIENT_ID,
            "plugin_version" to plugin.description.version,
            "instance_id" to instanceId,
            "intent" to intent.wire,
          ),
        ) ?: return StartResult.Error("Panel URL is not configured.")
      when (code) {
        HTTP_OK -> {
          val deviceCode = node.path("device_code").asText("")
          val userCode = node.path("user_code").asText("")
          if (deviceCode.isBlank() || userCode.isBlank()) {
            StartResult.Error("Panel returned an invalid response.")
          } else {
            StartResult.Started(
              deviceCode = deviceCode,
              userCode = userCode,
              verificationUri = node.path("verification_uri").asText(""),
              verificationUriComplete = node.path("verification_uri_complete").asText(""),
              expiresInSeconds =
                node
                  .path("expires_in")
                  .asLong(DEFAULT_EXPIRES)
                  .coerceIn(MIN_EXPIRES_SECONDS, MAX_EXPIRES_SECONDS),
              intervalSeconds =
                node
                  .path("interval")
                  .asLong(DEFAULT_INTERVAL)
                  .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
            )
          }
        }
        TOO_MANY_REQUESTS -> StartResult.Error("Too many attempts. Please wait a few minutes.")
        else -> StartResult.Error("Panel returned HTTP $code.")
      }
    } catch (e: Exception) {
      StartResult.Error("Could not reach the panel: ${e.message}")
    }
  }

  fun poll(deviceCode: String): PollResult {
    return try {
      val (code, node) =
        post("/api/v1/device/token", mapOf("device_code" to deviceCode))
          ?: return PollResult.Error("Panel URL is not configured.")
      if (code == HTTP_OK && node.path("status").asText("") == "approved") {
        val secret = node.path("secret_key").asText("")
        if (secret.isBlank()) {
          return PollResult.Error("Panel approved but returned no key.")
        }
        val server = node.path("server")
        PollResult.Approved(
          secretKey = secret,
          serverId = server.path("id").asText("").ifBlank { null },
          serverName = server.path("name").asText("").ifBlank { null },
          allowlistedIp = node.path("allowlisted_ip").asText("").ifBlank { null },
          inferenceUrl = node.path("inference_url").asText("").ifBlank { null },
        )
      } else {
        when (node.path("error").asText("")) {
          "authorization_pending" -> PollResult.Pending
          "slow_down" ->
            PollResult.SlowDown(
              node
                .path("interval")
                .asLong(DEFAULT_INTERVAL + SLOW_DOWN_EXTRA_SECONDS)
                .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
            )
          "access_denied" -> PollResult.Denied
          "expired_token" -> PollResult.Expired
          else -> PollResult.Error("Unexpected panel response (HTTP $code).")
        }
      }
    } catch (e: Exception) {
      PollResult.Error("Network error: ${e.message}")
    }
  }

  fun revoke(secretKey: String): RevokeResult {
    return try {
      val (code, node) =
        post("/api/v1/device/revoke", mapOf("secret_key" to secretKey))
          ?: return RevokeResult.Error("Panel URL is not configured.")
      val status = node.path("status").asText("")
      when {
        code == HTTP_OK && (status == "revoked" || status == "not_found") -> RevokeResult.Revoked
        code == TOO_MANY_REQUESTS ->
          RevokeResult.Error("Too many attempts. Please wait a few minutes.")
        else -> RevokeResult.Error("Panel returned HTTP $code.")
      }
    } catch (e: Exception) {
      RevokeResult.Error("Network error: ${e.message}")
    }
  }

  fun redeem(
    userCode: String,
    instanceId: String,
    hostname: String?,
    pluginVersion: String?,
  ): LinkResult {
    return try {
      val (code, node) =
        post(
          "/api/v1/device/redeem",
          mapOf(
            "user_code" to userCode,
            "instance_id" to instanceId,
            "hostname" to hostname,
            "plugin_version" to pluginVersion,
          ),
        ) ?: return LinkResult.Error("Panel URL is not configured.")
      when {
        code == HTTP_OK && node.path("status").asText("") == "linked" -> {
          val secret = node.path("secret_key").asText("")
          if (secret.isBlank()) {
            LinkResult.Error("Panel linked but returned no key.")
          } else {
            val server = node.path("server")
            LinkResult.Linked(
              secretKey = secret,
              serverId = server.path("id").asText("").ifBlank { null },
              serverName = server.path("name").asText("").ifBlank { null },
              allowlistedIp = node.path("allowlisted_ip").asText("").ifBlank { null },
              inferenceUrl = node.path("inference_url").asText("").ifBlank { null },
            )
          }
        }
        code == TOO_MANY_REQUESTS ->
          LinkResult.Error("Too many attempts. Please wait a few minutes.")
        code == GONE || node.path("error").asText("") == "expired_token" ->
          LinkResult.InvalidOrExpired
        node.path("error").asText("") == "invalid_request" -> LinkResult.Error("Invalid code.")
        else -> LinkResult.Error("Panel returned HTTP $code.")
      }
    } catch (e: Exception) {
      LinkResult.Error("Network error: ${e.message}")
    }
  }

  fun cancel(deviceCode: String) {
    try {
      post("/api/v1/device/cancel", mapOf("device_code" to deviceCode))
    } catch (e: Exception) {
      plugin.logger.fine("[Connect] cancel failed: ${e.message}")
    }
  }

  private fun post(path: String, body: Map<String, Any?>): Pair<Int, JsonNode>? {
    val base = configManager.connectPanelUrl.trim().trimEnd('/')
    if (!isUsablePanelUrl(base)) return null
    val request =
      HttpRequest.newBuilder(URI.create("$base$path"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("User-Agent", "Shard/" + plugin.description.version)
        .timeout(REQUEST_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    val payload = response.body().use(::readCapped)
    val node =
      if (payload.isBlank()) mapper.createObjectNode()
      else runCatching { mapper.readTree(payload) }.getOrElse { mapper.createObjectNode() }
    return response.statusCode() to node
  }

  private fun isUsablePanelUrl(base: String): Boolean =
    when {
      base.isBlank() -> false
      !isSecurePanelUrl(base) -> {
        plugin.logger.warning("[Connect] Refusing to contact the panel over an insecure URL: $base")
        false
      }
      else -> true
    }

  private companion object {
    const val CLIENT_ID = "shard-plugin"
    const val HTTP_OK = 200
    const val TOO_MANY_REQUESTS = 429
    const val GONE = 410
    const val DEFAULT_EXPIRES = 600L
    const val DEFAULT_INTERVAL = 5L
    const val MIN_EXPIRES_SECONDS = 60L
    const val MAX_EXPIRES_SECONDS = 3600L
    const val MIN_INTERVAL_SECONDS = 1L
    const val MAX_INTERVAL_SECONDS = 300L
    const val SLOW_DOWN_EXTRA_SECONDS = 5L
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
  }
}
