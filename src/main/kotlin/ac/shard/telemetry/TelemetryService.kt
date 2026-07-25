/*
 * This file is part of Shard - https://github.com/KaelusMC/Shard
 * Copyright (C) 2026 KaelusMC
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
package ac.shard.telemetry

import ac.shard.Shard
import ac.shard.api.event.PunishmentTriggeredEvent
import ac.shard.api.event.ShardEventBus
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigManager
import ac.shard.connect.CredentialsStore
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.PlayerDataManager
import ac.shard.scheduler.SchedulerService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import org.bukkit.Bukkit

@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class TelemetryService(
  private val plugin: Shard,
  private val configManager: ConfigManager,
  private val credentialsStore: CredentialsStore,
  private val scheduler: SchedulerService,
  private val playerDataManager: PlayerDataManager,
  private val eventBus: ShardEventBus,
) {
  private val mapper = ObjectMapper()
  private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
  private val punishmentDelta = LongAdder()

  private var handle: TaskHandle? = null
  private var startedAtMs: Long = 0L
  private var instanceId: String = ""

  @Volatile private var stopping = false
  @Volatile private var lastOk: Boolean? = null
  @Volatile private var quota: QuotaSnapshot? = null

  val quotaSnapshot: QuotaSnapshot?
    get() = quota

  private data class Beat(
    val online: Int,
    val suspicious: Int,
    val tps: Double?,
    val punishments: Long,
  )

  fun start() {
    startedAtMs = System.currentTimeMillis()
    instanceId = credentialsStore.instanceId()
    eventBus.subscribe(this, PunishmentTriggeredEvent::class.java) { punishmentDelta.increment() }
    val jitter = ThreadLocalRandom.current().nextLong(PERIOD_TICKS)
    handle = scheduler.runTimer({ tick() }, jitter, PERIOD_TICKS)
  }

  fun stop() {
    stopping = true
    runCatching { eventBus.unregisterAll(this) }
    runCatching { handle?.cancel() }
    handle = null
  }

  fun sendFarewell() {
    val key = configManager.aiApiKey
    if (!configManager.isTelemetryEnabled() || !keyValid(key) || instanceId.isEmpty()) {
      return
    }
    val url = deviceUrl("heartbeat") ?: return
    val beat = Beat(online = 0, suspicious = 0, tps = null, punishments = punishmentDelta.sum())
    try {
      client
        .sendAsync(
          buildRequest(url, key, beat, stopping = true, FAREWELL_TIMEOUT),
          HttpResponse.BodyHandlers.discarding(),
        )
        .get(FAREWELL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
      plugin.logger.fine("[Telemetry] farewell beat failed: ${e.message}")
    }
  }

  private fun tick() {
    val key = configManager.aiApiKey
    if (!configManager.isTelemetryEnabled() || !keyValid(key) || deviceUrl("heartbeat") == null) {
      return
    }
    val beat =
      Beat(
        online = Bukkit.getOnlinePlayers().size,
        suspicious = suspiciousCount(),
        tps = runCatching { Bukkit.getServer().getTPS()[0] }.getOrNull(),
        punishments = punishmentDelta.sumThenReset(),
      )
    scheduler.runAsync { send(key, beat) }
  }

  private fun keyValid(key: String): Boolean = key.isNotBlank() && key != PLACEHOLDER_KEY

  fun fetchQuota(): Int? {
    val key = configManager.aiApiKey
    val url = deviceUrl("quota")
    if (!keyValid(key) || url == null) return null
    return runCatching {
        val request =
          HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("X-API-Key", key)
            .header("User-Agent", "Shard/" + plugin.description.version)
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
          null
        } else {
          applyServerState(response.body(), applyParams = false)
        }
      }
      .onFailure { plugin.logger.fine("[Telemetry] quota fetch failed: ${it.message}") }
      .getOrNull()
  }

  private fun suspiciousCount(): Int =
    playerDataManager.getPlayers().count { player ->
      val check = player.checkManager.getCheck(AiCheck::class.java)
      check != null && check.buffer > SUSPICIOUS_BUFFER
    }

  private fun buildRequest(
    url: String,
    key: String,
    beat: Beat,
    stopping: Boolean,
    timeout: Duration,
  ): HttpRequest {
    val body =
      buildMap<String, Any?> {
        put("instance_id", instanceId)
        configManager.telemetryGroupId?.let { put("group_id", it) }
        put("online", beat.online)
        put("suspicious", beat.suspicious)
        put("tps", beat.tps)
        put("plugin_version", plugin.description.version)
        put("uptime_seconds", (System.currentTimeMillis() - startedAtMs) / MILLIS_PER_SECOND)
        if (beat.punishments > 0) put("punishments", beat.punishments)
        if (stopping) put("stopping", true)
      }
    return HttpRequest.newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("X-API-Key", key)
      .header("User-Agent", "Shard/" + plugin.description.version)
      .timeout(timeout)
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
      .build()
  }

  private fun send(key: String, beat: Beat) {
    if (stopping) return
    val url = deviceUrl("heartbeat") ?: return
    try {
      val request = buildRequest(url, key, beat, stopping = false, REQUEST_TIMEOUT)
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      val ok = response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX
      if (ok) {
        applyServerState(response.body(), applyParams = true)
      }
      mark(ok)
    } catch (e: Exception) {
      if (beat.punishments > 0) punishmentDelta.add(beat.punishments)
      mark(false, e.message)
    }
  }

  private fun applyServerState(body: String?, applyParams: Boolean): Int? {
    val root = if (body.isNullOrBlank()) null else runCatching { mapper.readTree(body) }.getOrNull()
    if (root == null) return null
    fun field(name: String): JsonNode? =
      root.path(name).takeUnless { it.isMissingNode || it.isNull }
    val used = field("quota_used_percent")?.asInt()
    if (used != null) quota = QuotaSnapshot(used)
    if (applyParams) {
      val seq = field("sequence")?.asInt()
      val step = field("step")?.asInt()
      val model = field("model")?.asText()
      if (seq != null || step != null || model != null) {
        configManager.updateAiParams(seq, step, model)
      }
    }
    return used
  }

  private fun deviceUrl(path: String): String? {
    val inference = configManager.aiServerUrl.trim().trimEnd('/')
    val base = inference.substringBeforeLast('/', "")
    return if (base.isBlank()) null else "$base/device/$path"
  }

  private fun mark(ok: Boolean, error: String? = null) {
    if (lastOk == ok) return
    lastOk = ok
    if (ok) {
      plugin.logger.fine("[Telemetry] reporting online")
    } else {
      plugin.logger.fine("[Telemetry] heartbeat unavailable: ${error ?: "non-2xx"}")
    }
  }

  private companion object {
    const val PLACEHOLDER_KEY = "API-KEY"
    const val PERIOD_TICKS = 600L
    const val MILLIS_PER_SECOND = 1000L
    const val SUSPICIOUS_BUFFER = 10.0
    const val HTTP_OK_MIN = 200
    const val HTTP_OK_MAX = 299
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    val FAREWELL_TIMEOUT: Duration = Duration.ofSeconds(2)
  }
}

data class QuotaSnapshot(val usedPercent: Int)
