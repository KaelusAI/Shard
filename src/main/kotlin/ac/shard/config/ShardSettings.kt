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
package ac.shard.config

import ac.shard.ai.label.LabelKey
import ac.shard.connect.Credentials
import ac.shard.debug.DebugCategory
import ac.shard.region.RegionCheckMode
import java.util.EnumSet
import java.util.Locale
import java.util.regex.Pattern
import org.spongepowered.configurate.CommentedConfigurationNode

data class ShardSettings(
  val ai: AiConnection,
  val localAi: LocalAiSettings,
  val panelUrl: String,
  val telemetry: TelemetrySettings,
  val collect: CollectWindow,
  val buffer: PersistentBufferSettings,
  val regions: RegionSettings,
  val packets: PacketSettings,
  val clientBrand: ClientBrandSettings,
  val bedrockExempt: Boolean,
  val editorConsoleOnly: Boolean,
  val debugCategories: Set<DebugCategory>,
) {
  companion object {
    private const val PLACEHOLDER_KEY = "API-KEY"

    val DEFAULT: ShardSettings = read(ConfigView(CommentedConfigurationNode.root()), null, 0) {}

    fun read(
      config: ConfigView,
      credentials: Credentials?,
      generation: Long,
      warn: (String) -> Unit,
    ): ShardSettings {
      val names =
        config
          .getStringMap("ai.labels.names")
          .mapNotNull { (key, name) -> LabelKey.canonical(key)?.let { it to name } }
          .toMap()
      val split =
        when (config.getString("ai.labels.split", "auto").trim().lowercase(Locale.ROOT)) {
          "never",
          "false" -> false
          else -> true
        }
      return ShardSettings(
        ai = connection(config, credentials, warn),
        localAi =
          LocalAiSettings(split, names, ModelOverride.parse(config.node("ai.models")), generation),
        panelUrl = config.getString("connect.panel-url", "https://app.shard.ac"),
        telemetry = TelemetrySettings.read(config),
        collect = CollectWindow.read(config),
        buffer = PersistentBufferSettings.read(config, warn),
        regions = RegionSettings.read(config, warn),
        packets = PacketSettings.read(config),
        clientBrand = ClientBrandSettings.read(config, warn),
        bedrockExempt = config.getBoolean("exemptions.bedrock", true),
        editorConsoleOnly = config.getBoolean("editor.console-only", false),
        debugCategories =
          DebugCategory.entries.filterTo(EnumSet.noneOf(DebugCategory::class.java)) {
            config.getBoolean("debug.categories.${it.configKey}", false)
          },
      )
    }

    private fun connection(
      config: ConfigView,
      credentials: Credentials?,
      warn: (String) -> Unit,
    ): AiConnection {
      val configKey = config.getString("ai.api-key", PLACEHOLDER_KEY)
      val linkedKey = credentials?.secretKey?.takeIf { it.isNotBlank() }
      if (linkedKey != null && configKey.isNotBlank() && configKey != PLACEHOLDER_KEY) {
        warn(
          "config.yml still has ai.api-key set, but this server is linked via /shard connect - " +
            "the config key is ignored. Remove it from config.yml."
        )
      }
      val linkedUrl = credentials?.inferenceUrl?.takeIf { it.isNotBlank() }
      return AiConnection(
        enabled = linkedUrl != null || config.getBoolean("ai.enabled", false),
        url = linkedUrl ?: config.getString("ai.server", ""),
        key = linkedKey ?: configKey,
        gzip = config.getBoolean("ai.gzip", true),
        batchMaxSize = config.getInt("ai.batch.max-size", AiConnection.DEFAULT_BATCH_MAX_SIZE),
        batchMaxDelayMs =
          config.getLong("ai.batch.max-delay-ms", AiConnection.DEFAULT_BATCH_MAX_DELAY_MS),
        backoff =
          Backoff(
            config.getLong("ai.backoff.initial-duration", Backoff.DEFAULT.initialSeconds),
            config.getLong("ai.backoff.max-duration", Backoff.DEFAULT.maxSeconds),
            config.getDouble("ai.backoff.multiplier", Backoff.DEFAULT.multiplier),
          ),
      )
    }
  }
}

data class TelemetrySettings(
  val enabled: Boolean,
  val groupId: String?,
  val cloudPunishments: CloudPunishments,
) {
  companion object {
    fun read(config: ConfigView): TelemetrySettings {
      val enabled = config.getBoolean("telemetry.enabled", true)
      return TelemetrySettings(
        enabled = enabled,
        groupId =
          System.getenv("SHARD_GROUP_ID")?.trim()?.takeIf { it.isNotBlank() }
            ?: config.getString("telemetry.group-id", "").trim().takeIf { it.isNotBlank() },
        cloudPunishments =
          if (enabled) CloudPunishments.parse(config.getString("cloud.punishments", "sync"))
          else CloudPunishments.OFF,
      )
    }
  }
}

data class CollectWindow(val pre: Int, val post: Int) {
  companion object {
    private const val DEFAULT_WINDOW = 128

    fun read(config: ConfigView): CollectWindow =
      CollectWindow(
        config.getInt("ai.collect.pre-window", DEFAULT_WINDOW),
        config.getInt("ai.collect.post-window", DEFAULT_WINDOW),
      )
  }
}

data class PersistentBufferSettings(
  val enabled: Boolean,
  val ttlMillis: Long,
  val cap: Double,
  val decayPerHour: Double,
  val disconnectWindowMillis: Long,
  val saveThreshold: Double,
) {
  companion object {
    private const val MILLIS_PER_SEC = 1000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val DEFAULT_TTL_HOURS = 48L
    private const val DEFAULT_CAP = 40.0
    private const val DEFAULT_DECAY = 2.0
    private const val DEFAULT_DISCONNECT_WINDOW_SECS = 300L
    private const val DEFAULT_SAVE_THRESHOLD = 1.0

    fun read(config: ConfigView, warn: (String) -> Unit): PersistentBufferSettings {
      val ttlHours = config.getLong("ai.persistent-buffer.ttl-hours", DEFAULT_TTL_HOURS)
      if (ttlHours <= 0L) {
        warn(
          "[Config] ai.persistent-buffer.ttl-hours=$ttlHours is invalid, using $DEFAULT_TTL_HOURS"
        )
      }
      return PersistentBufferSettings(
        enabled = config.getBoolean("ai.persistent-buffer.enabled", true),
        ttlMillis = ttlHours.coerceAtLeast(1L) * MILLIS_PER_HOUR,
        cap = config.getDouble("ai.persistent-buffer.cap-on-restore", DEFAULT_CAP),
        decayPerHour = config.getDouble("ai.persistent-buffer.decay-rate-per-hour", DEFAULT_DECAY),
        disconnectWindowMillis =
          config.getLong(
            "ai.persistent-buffer.disconnect-window-seconds",
            DEFAULT_DISCONNECT_WINDOW_SECS,
          ) * MILLIS_PER_SEC,
        saveThreshold =
          config.getDouble("ai.persistent-buffer.save-threshold", DEFAULT_SAVE_THRESHOLD),
      )
    }
  }
}

data class RegionSettings(
  val worldGuard: Boolean,
  val flagOverridesList: Boolean,
  val disabled: Map<String, List<String>>,
  val mode: RegionCheckMode,
) {
  companion object {
    fun read(config: ConfigView, warn: (String) -> Unit): RegionSettings =
      RegionSettings(
        worldGuard = config.getBoolean("ai.worldguard.enabled", true),
        flagOverridesList = config.getBoolean("ai.worldguard.flag-overrides-list", true),
        disabled = disabledRegions(config, warn),
        mode = RegionCheckMode.fromConfig(config.getString("ai.worldguard.mode", "skip-detection")),
      )

    private fun disabledRegions(
      config: ConfigView,
      warn: (String) -> Unit,
    ): Map<String, List<String>> {
      val mapRegions = config.getStringListMap("ai.worldguard.disabled-regions")
      return if (mapRegions.isNotEmpty()) {
        mapRegions
          .mapKeys { it.key.lowercase(Locale.ROOT) }
          .mapValues { entry -> entry.value.map { it.lowercase(Locale.ROOT) } }
      } else {
        legacyDisabledRegions(config, warn)
      }
    }

    private fun legacyDisabledRegions(
      config: ConfigView,
      warn: (String) -> Unit,
    ): Map<String, List<String>> {
      val legacyList = config.getStringList("ai.worldguard.disabled-regions")
      if (legacyList.isEmpty()) return emptyMap()

      warn(
        "[Config] ai.worldguard.disabled-regions uses deprecated " +
          "region:world format. Please migrate to the new map format."
      )
      val result = mutableMapOf<String, MutableList<String>>()
      for (entry in legacyList) {
        val lower = entry.lowercase(Locale.ROOT)
        val region = lower.substringBefore(':')
        val world = if (':' in lower) lower.substringAfter(':') else "*"
        result.getOrPut(world) { mutableListOf() }.add(region)
      }
      return result
    }
  }
}

data class PacketSettings(
  val cancelDuplicate: Boolean,
  val forceCancelDuplicate: Boolean,
  val ignoreDuplicateRotation: Boolean,
) {
  companion object {
    fun read(config: ConfigView): PacketSettings =
      PacketSettings(
        cancelDuplicate = config.getBoolean("cancel-duplicate-packet", true),
        forceCancelDuplicate = config.getBoolean("force-cancel-duplicate-packet", false),
        ignoreDuplicateRotation = config.getBoolean("ignore-duplicate-packet-rotation", true),
      )
  }
}

class ClientBrandSettings(
  private val ignored: List<Pattern>,
  val disconnectBlacklistedForge: Boolean,
) {
  fun isIgnored(brand: String): Boolean = ignored.any { it.matcher(brand).find() }

  companion object {
    fun read(config: ConfigView, warn: (String) -> Unit): ClientBrandSettings =
      ClientBrandSettings(
        config.getStringList("client-brand.ignored-clients").mapNotNull { pattern ->
          runCatching { Pattern.compile(pattern) }
            .onFailure { warn("[ClientBrand] Invalid regex pattern in config: $pattern") }
            .getOrNull()
        },
        config.getBoolean("client-brand.disconnect-blacklisted-forge-versions", true),
      )
  }
}
