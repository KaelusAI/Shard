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

import ac.shard.api.event.punishment.PunishmentEvent
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.config.ShardSettings
import ac.shard.connect.CredentialsStore
import ac.shard.detection.SuspicionPolicy
import ac.shard.http.HttpBodies
import ac.shard.http.HttpOutcome
import ac.shard.http.Json
import ac.shard.http.ShardHttp
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.PlayerDataManager
import ac.shard.punishment.cloud.PunishmentSync
import ac.shard.scheduler.SchedulerService
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.logging.Logger
import tools.jackson.databind.JsonNode

@Suppress("TooGenericExceptionCaught", "TooManyFunctions", "LongParameterList")
class TelemetryService(
  private val logger: Logger,
  private val http: ShardHttp,
  private val configManager: ConfigManager,
  private val credentialsStore: CredentialsStore,
  private val scheduler: SchedulerService,
  private val playerDataManager: PlayerDataManager,
  private val events: ShardEvents,
  private val plugin: ac.shard.Shard,
  private val punishmentSync: PunishmentSync,
  private val suspicion: SuspicionPolicy,
) {
  private val mapper = Json.mapper
  private val client = http.client
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
    events
      .subscription(plugin, PunishmentEvent::class.java)
      .monitor()
      .ignoreCancelled(true)
      .subscribe {
        punishmentDelta.increment()
      }
    val jitter = ThreadLocalRandom.current().nextLong(PERIOD_TICKS)
    handle = scheduler.runTimer({ tick() }, jitter, PERIOD_TICKS)
  }

  fun stop() {
    stopping = true
    runCatching { events.unsubscribeAll(plugin) }
    runCatching { handle?.cancel() }
    handle = null
  }

  fun sendFarewell() {
    val settings = configManager.settings
    if (!settings.telemetry.enabled || !keyValid(settings.ai.key) || instanceId.isEmpty()) {
      return
    }
    val url = settings.ai.deviceUrl("heartbeat") ?: return
    val beat = Beat(online = 0, suspicious = 0, tps = null, punishments = punishmentDelta.sum())
    try {
      client
        .sendAsync(
          buildRequest(url, settings, beat, stopping = true, FAREWELL_TIMEOUT),
          HttpResponse.BodyHandlers.discarding(),
        )
        .get(FAREWELL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
      logger.fine("[Telemetry] farewell beat failed: ${e.message}")
    }
  }

  private fun tick() {
    val settings = configManager.settings
    val url = settings.ai.deviceUrl("heartbeat")
    if (!settings.telemetry.enabled || !keyValid(settings.ai.key) || url == null) {
      return
    }
    val beat =
      Beat(
        online = plugin.server.onlinePlayers.size,
        suspicious = suspiciousCount(),
        tps = runCatching { plugin.server.tps[0] }.getOrNull(),
        punishments = punishmentDelta.sumThenReset(),
      )
    scheduler.runAsync { send(url, settings, beat) }
  }

  private fun keyValid(key: String): Boolean = key.isNotBlank() && key != PLACEHOLDER_KEY

  fun fetchQuota(): Int? {
    val ai = configManager.settings.ai
    val key = ai.key
    val url = ai.deviceUrl("quota")
    if (!keyValid(key) || url == null) return null
    return runCatching {
        val request =
          http
            .keyed(URI.create(url), key)
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        val response = client.send(request, HttpBodies.text(HttpBodies.PANEL_LIMIT))
        if (HttpOutcome.of(response.statusCode()) != HttpOutcome.OK) {
          null
        } else {
          applyServerState(response.body(), applyParams = false)
        }
      }
      .onFailure { logger.fine("[Telemetry] quota fetch failed: ${it.message}") }
      .getOrNull()
  }

  private fun suspiciousCount(): Int = playerDataManager.getPlayers().count(suspicion::isSuspicious)

  private fun buildRequest(
    url: String,
    settings: ShardSettings,
    beat: Beat,
    stopping: Boolean,
    timeout: Duration,
  ): HttpRequest {
    val body =
      buildMap<String, Any?> {
        put("instance_id", instanceId)
        settings.telemetry.groupId?.let { put("group_id", it) }
        put("online", beat.online)
        put("suspicious", beat.suspicious)
        put("tps", beat.tps)
        put("plugin_version", http.pluginVersion)
        put("uptime_seconds", (System.currentTimeMillis() - startedAtMs) / MILLIS_PER_SECOND)
        put("model_config", configManager.modelConfigFingerprint())
        put("local", settings.localAi.narrowing())
        if (beat.punishments > 0) put("punishments", beat.punishments)
        if (stopping) put("stopping", true) else punishmentSync.beat()?.let { put("punishrev", it) }
      }
    return http
      .keyed(URI.create(url), settings.ai.key)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .timeout(timeout)
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
      .build()
  }

  private fun send(url: String, settings: ShardSettings, beat: Beat) {
    if (stopping) return
    try {
      val request = buildRequest(url, settings, beat, stopping = false, REQUEST_TIMEOUT)
      val response = client.send(request, HttpBodies.text(HttpBodies.PANEL_LIMIT))
      val ok = HttpOutcome.of(response.statusCode()) == HttpOutcome.OK
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
    val used = field("quota_used_percent")?.asInt(0)
    if (used != null) quota = QuotaSnapshot(used)
    if (applyParams) {
      field("model")
        ?.asString("")
        ?.takeIf(String::isNotBlank)
        ?.let(configManager::notePanelModelName)
      field("punishrev")?.takeIf { it.canConvertToLong() }?.asLong(0L)?.let(punishmentSync::onBeat)
    }
    return used
  }

  private fun mark(ok: Boolean, error: String? = null) {
    if (lastOk == ok) return
    lastOk = ok
    if (ok) {
      logger.fine("[Telemetry] reporting online")
    } else {
      logger.fine("[Telemetry] heartbeat unavailable: ${error ?: "non-2xx"}")
    }
  }

  private companion object {
    const val PLACEHOLDER_KEY = "API-KEY"
    const val PERIOD_TICKS = 600L
    const val MILLIS_PER_SECOND = 1000L
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    val FAREWELL_TIMEOUT: Duration = Duration.ofSeconds(2)
  }
}

data class QuotaSnapshot(val usedPercent: Int)
