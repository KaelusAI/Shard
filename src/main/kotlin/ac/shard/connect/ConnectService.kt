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

import ac.shard.config.ConfigManager
import ac.shard.http.HttpBodies
import ac.shard.http.HttpOutcome
import ac.shard.http.Json
import ac.shard.http.PanelReplies
import ac.shard.http.ShardHttp
import ac.shard.http.isSecureEndpoint
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.logging.Logger
import tools.jackson.databind.JsonNode

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

  data class Approved(val credentials: Credentials) : PollResult

  data object Denied : PollResult

  data object Expired : PollResult

  data class Error(val message: String) : PollResult
}

sealed interface RevokeResult {
  data object Revoked : RevokeResult

  data class Error(val message: String) : RevokeResult
}

sealed interface LinkResult {
  data class Linked(val credentials: Credentials) : LinkResult

  data object InvalidOrExpired : LinkResult

  data class Error(val message: String) : LinkResult
}

enum class LinkIntent(val wire: String) {
  CONNECT("connect"),
  SETUP("setup"),
}

@Suppress("TooGenericExceptionCaught", "ReturnCount")
class ConnectService(
  private val logger: Logger,
  private val http: ShardHttp,
  private val configManager: ConfigManager,
) {
  private val mapper = Json.mapper

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
            "plugin_version" to http.pluginVersion,
            "instance_id" to instanceId,
            "intent" to intent.wire,
          ),
        ) ?: return StartResult.Error("Panel URL is not configured.")
      when (HttpOutcome.of(code)) {
        HttpOutcome.OK -> {
          val deviceCode = node.path("device_code").asString("")
          val userCode = node.path("user_code").asString("")
          if (deviceCode.isBlank() || userCode.isBlank()) {
            StartResult.Error("Panel returned an invalid response.")
          } else {
            StartResult.Started(
              deviceCode = deviceCode,
              userCode = userCode,
              verificationUri = node.path("verification_uri").asString(""),
              verificationUriComplete = node.path("verification_uri_complete").asString(""),
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
        HttpOutcome.RATE_LIMITED -> StartResult.Error(PanelReplies.RATE_LIMITED)
        else -> StartResult.Error(PanelReplies.failed(code))
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
      if (
        HttpOutcome.of(code) == HttpOutcome.OK && node.path("status").asString("") == "approved"
      ) {
        val secret = node.path("secret_key").asString("")
        if (secret.isBlank()) {
          return PollResult.Error("Panel approved but returned no key.")
        }
        PollResult.Approved(credentialsOf(secret, node))
      } else {
        when (node.path("error").asString("")) {
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

  private fun credentialsOf(secret: String, node: JsonNode): Credentials {
    val server = node.path("server")
    return Credentials(
      secretKey = secret,
      serverId = server.path("id").asString("").ifBlank { null },
      serverName = server.path("name").asString("").ifBlank { null },
      allowlistedIp = node.path("allowlisted_ip").asString("").ifBlank { null },
      inferenceUrl = node.path("inference_url").asString("").ifBlank { null },
    )
  }

  fun revoke(secretKey: String): RevokeResult {
    return try {
      val (code, node) =
        post("/api/v1/device/revoke", mapOf("secret_key" to secretKey))
          ?: return RevokeResult.Error("Panel URL is not configured.")
      val status = node.path("status").asString("")
      when {
        HttpOutcome.of(code) == HttpOutcome.OK && (status == "revoked" || status == "not_found") ->
          RevokeResult.Revoked
        HttpOutcome.of(code) == HttpOutcome.RATE_LIMITED ->
          RevokeResult.Error(PanelReplies.RATE_LIMITED)
        else -> RevokeResult.Error(PanelReplies.failed(code))
      }
    } catch (e: Exception) {
      RevokeResult.Error("Network error: ${e.message}")
    }
  }

  fun redeem(userCode: String, instanceId: String, hostname: String?): LinkResult {
    return try {
      val (code, node) =
        post(
          "/api/v1/device/redeem",
          mapOf(
            "user_code" to userCode,
            "instance_id" to instanceId,
            "hostname" to hostname,
            "plugin_version" to http.pluginVersion,
          ),
        ) ?: return LinkResult.Error("Panel URL is not configured.")
      when {
        HttpOutcome.of(code) == HttpOutcome.OK && node.path("status").asString("") == "linked" -> {
          val secret = node.path("secret_key").asString("")
          if (secret.isBlank()) {
            LinkResult.Error("Panel linked but returned no key.")
          } else {
            LinkResult.Linked(credentialsOf(secret, node))
          }
        }
        HttpOutcome.of(code) == HttpOutcome.RATE_LIMITED ->
          LinkResult.Error(PanelReplies.RATE_LIMITED)
        HttpOutcome.of(code) == HttpOutcome.GONE ||
          node.path("error").asString("") == "expired_token" -> LinkResult.InvalidOrExpired
        node.path("error").asString("") == "invalid_request" -> LinkResult.Error("Invalid code.")
        else -> LinkResult.Error(PanelReplies.failed(code))
      }
    } catch (e: Exception) {
      LinkResult.Error("Network error: ${e.message}")
    }
  }

  fun cancel(deviceCode: String) {
    try {
      post("/api/v1/device/cancel", mapOf("device_code" to deviceCode))
    } catch (e: Exception) {
      logger.fine("[Connect] cancel failed: ${e.message}")
    }
  }

  private fun post(path: String, body: Map<String, Any?>): Pair<Int, JsonNode>? {
    val base = configManager.settings.panelUrl.trim().trimEnd('/')
    if (!isUsablePanelUrl(base)) return null
    val request =
      HttpRequest.newBuilder(URI.create("$base$path"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("User-Agent", http.userAgent)
        .timeout(REQUEST_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build()
    val response = http.client.send(request, HttpBodies.text(HttpBodies.PANEL_LIMIT))
    val payload = response.body()
    val node =
      if (payload.isBlank()) mapper.createObjectNode()
      else runCatching { mapper.readTree(payload) }.getOrElse { mapper.createObjectNode() }
    return response.statusCode() to node
  }

  private fun isUsablePanelUrl(base: String): Boolean =
    when {
      base.isBlank() -> false
      !isSecureEndpoint(base) -> {
        logger.warning("[Connect] Refusing to contact the panel over an insecure URL: $base")
        false
      }
      else -> true
    }

  private companion object {
    const val CLIENT_ID = "shard-plugin"
    const val DEFAULT_EXPIRES = 600L
    const val DEFAULT_INTERVAL = 5L
    const val MIN_EXPIRES_SECONDS = 60L
    const val MAX_EXPIRES_SECONDS = 3600L
    const val MIN_INTERVAL_SECONDS = 1L
    const val MAX_INTERVAL_SECONDS = 300L
    const val SLOW_DOWN_EXTRA_SECONDS = 5L
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
  }
}
