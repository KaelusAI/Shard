/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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

import ac.shard.Shard
import ac.shard.connect.CredentialsStore
import ac.shard.debug.DebugCategory
import ac.shard.region.RegionCheckMode
import java.io.File
import java.util.EnumSet
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import ru.vyarus.yaml.updater.YamlUpdater

class ConfigManager(private val plugin: Shard, private val credentialsStore: CredentialsStore) {
  private val modelStore = ModelStore(plugin)

  var config: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  var punishments: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  var monitorConfig: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  private var aiEnabled = false
  var aiServerUrl: String = ""
    private set

  var aiApiKey: String = ""
    private set

  var connectPanelUrl: String = ""
    private set

  private var telemetryEnabled = true

  var telemetryGroupId: String? = null
    private set

  @Volatile
  var aiSequence: Int = 0
    private set

  @Volatile
  var aiStep: Int = 0
    private set

  @Volatile
  var aiModel: String? = null
    private set

  var aiContinuous: Boolean = false
    private set

  var aiFlag: Double = 0.0
    private set

  var aiResetOnFlag: Double = 0.0
    private set

  var aiBufferMultiplier: Double = 0.0
    private set

  var aiBufferDecrease: Double = 0.0
    private set

  private var aiDamageReductionEnabled = false
  var aiDamageReductionProb: Double = 0.0
    private set

  var aiDamageReductionMultiplier: Double = 0.0
    private set

  private var aiWorldGuardEnabled = false
  var aiDisabledRegions: Map<String, List<String>> = emptyMap()
    private set

  var regionCheckMode: RegionCheckMode = RegionCheckMode.SKIP_DETECTION
    private set

  private var bedrockExemptEnabled = false

  var persistentBufferEnabled: Boolean = false
    private set

  var persistentBufferTtlMillis: Long = 0L
    private set

  var persistentBufferCap: Double = 0.0
    private set

  var persistentBufferDecayPerHour: Double = 0.0
    private set

  var persistentBufferDisconnectWindowMillis: Long = 0L
    private set

  var persistentBufferSaveThreshold: Double = 0.0
    private set

  var batchEnabled: Boolean = true
    private set

  var batchMaxSize: Int = 0
    private set

  var batchMaxDelayMs: Long = 0L
    private set

  var retryMaxAttempts: Int = 0
    private set

  var retryInitialDelayMs: Long = 0L
    private set

  var retryMaxDelayMs: Long = 0L
    private set

  var retryMultiplier: Double = 0.0
    private set

  var retryJitter: Double = 0.0
    private set

  private var ignoredClientPatterns: List<Pattern> = emptyList()
  private var disconnectBlacklistedForge = false

  var suspiciousAlertsBuffer: Double = 0.0
    private set

  var cancelDuplicatePacket: Boolean = true
    private set

  var forceCancelDuplicatePacket: Boolean = false
    private set

  var ignoreDuplicatePacketRotation: Boolean = true
    private set

  private var debugEnabled = false
  var enabledDebugCategories: Set<DebugCategory> = emptySet()
    private set

  init {
    loadConfigs()
  }

  fun reloadConfig() {
    loadConfigs()
  }

  @Synchronized
  fun updateAiParams(sequence: Int?, step: Int?, model: String? = null) {
    var changed = false
    if (sequence != null && sequence >= MIN_AI_SEQUENCE && sequence != aiSequence) {
      plugin.logger.info("[Config] AI sequence $aiSequence -> $sequence (from server)")
      aiSequence = sequence
      changed = true
    }
    if (step != null && step >= MIN_AI_STEP && step != aiStep) {
      plugin.logger.info("[Config] AI step $aiStep -> $step (from server)")
      aiStep = step
      changed = true
    }
    if (!model.isNullOrBlank() && model != aiModel) {
      plugin.logger.info("[Config] AI model $aiModel -> $model (from server)")
      aiModel = model
      changed = true
    }
    if (changed) modelStore.write(aiSequence, aiStep, aiModel)
  }

  fun isAiEnabled(): Boolean = aiEnabled

  fun isTelemetryEnabled(): Boolean = telemetryEnabled

  fun isAiDamageReductionEnabled(): Boolean = aiDamageReductionEnabled

  fun isAiWorldGuardEnabled(): Boolean = aiWorldGuardEnabled

  fun isBedrockExemptEnabled(): Boolean = bedrockExemptEnabled

  fun isDisconnectBlacklistedForge(): Boolean = disconnectBlacklistedForge

  fun isDebugEnabled(): Boolean = debugEnabled

  private fun loadConfigs() {
    if (!plugin.dataFolder.exists()) {
      plugin.dataFolder.mkdirs()
    }

    config = loadConfig("config.yml", migrate = true)
    punishments = loadConfig("punishments.yml")
    monitorConfig = loadConfig("monitor.yml", migrate = true)

    loadValues()
  }

  private fun loadConfig(fileName: String, migrate: Boolean = false): ConfigView {
    val file = File(plugin.dataFolder, fileName)
    if (!file.exists()) {
      plugin.saveResource(fileName, false)
    }

    if (migrate) {
      runMigration(file, fileName)
    }

    return try {
      val loader = YamlConfigurationLoader.builder().path(file.toPath()).build()
      ConfigView(loader.load())
    } catch (e: Exception) {
      plugin.logger.severe("Failed to load $fileName: ${e.message}")
      ConfigView(CommentedConfigurationNode.root())
    }
  }

  private fun runMigration(file: File, fileName: String) {
    val updateStream = javaClass.classLoader.getResourceAsStream(fileName) ?: return

    val currentVersion = ConfigMigrations.readVersion(file, fileName)
    val drops = ConfigMigrations.forcedDropsForUpgradeFrom(currentVersion, fileName)

    val report =
      runCatching {
          YamlUpdater.create(file, updateStream).backup(true).deleteProps(drops).update()
        }
        .onFailure {
          plugin.logger.warning("[Config] Migration of $fileName failed: ${it.message}")
        }
        .getOrNull()

    if (report != null && report.isConfigChanged) {
      val added = report.added.map { it.path }
      val removed = report.removed.map { it.path }
      if (added.isNotEmpty()) {
        plugin.logger.info(
          "[Config] Added ${added.size} key(s) to $fileName: ${added.joinToString(", ")}"
        )
      }
      if (removed.isNotEmpty()) {
        plugin.logger.info(
          "[Config] Removed ${removed.size} key(s) from $fileName: ${removed.joinToString(", ")}"
        )
      }
      report.backup?.let {
        plugin.logger.info("[Config] Backup saved to ${it.name} before migrating $fileName")
      }
    }
  }

  private fun loadValues() {
    aiEnabled = config.getBoolean("ai.enabled", false)
    aiServerUrl = config.getString("ai.server", "")
    val configKey = config.getString("ai.api-key", "API-KEY")
    aiApiKey = configKey
    val credentials = credentialsStore.read()
    credentials
      ?.secretKey
      ?.takeIf { it.isNotBlank() }
      ?.let {
        aiApiKey = it
        if (configKey.isNotBlank() && configKey != "API-KEY") {
          plugin.logger.warning(
            "config.yml still has ai.api-key set, but this server is linked via /shard connect - " +
              "the config key is ignored. Remove it from config.yml."
          )
        }
      }
    credentials
      ?.inferenceUrl
      ?.takeIf { it.isNotBlank() }
      ?.let {
        aiServerUrl = it
        aiEnabled = true
      }

    connectPanelUrl = config.getString("connect.panel-url", "https://app.shard.ac")
    telemetryEnabled = config.getBoolean("telemetry.enabled", true)
    telemetryGroupId =
      System.getenv("SHARD_GROUP_ID")?.trim()?.takeIf { it.isNotBlank() }
        ?: config.getString("telemetry.group-id", "").trim().takeIf { it.isNotBlank() }
    aiSequence = modelStore.readSequence() ?: DEFAULT_AI_SEQUENCE
    aiStep = modelStore.readStep() ?: DEFAULT_AI_STEP
    aiModel = modelStore.readModel()
    aiContinuous = config.getBoolean("ai.continuous", false)

    aiFlag = config.getDouble("ai.buffer.flag", 50.0)
    aiResetOnFlag = config.getDouble("ai.buffer.reset-on-flag", 25.0)
    aiBufferMultiplier = config.getDouble("ai.buffer.multiplier", 100.0)
    aiBufferDecrease = config.getDouble("ai.buffer.decrease", 0.25)

    aiDamageReductionEnabled = config.getBoolean("ai.damage-reduction.enabled", true)
    aiDamageReductionProb = config.getDouble("ai.damage-reduction.prob", 0.9)
    aiDamageReductionMultiplier = config.getDouble("ai.damage-reduction.multiplier", 1.0)

    aiWorldGuardEnabled = config.getBoolean("ai.worldguard.enabled", true)
    aiDisabledRegions = loadDisabledRegions()
    regionCheckMode =
      RegionCheckMode.fromConfig(config.getString("ai.worldguard.mode", "skip-detection"))

    bedrockExemptEnabled = config.getBoolean("exemptions.bedrock", true)

    persistentBufferEnabled = config.getBoolean("ai.persistent-buffer.enabled", true)
    val ttlHours =
      config.getLong("ai.persistent-buffer.ttl-hours", DEFAULT_BUFFER_TTL_HOURS).also {
        if (it <= 0L) {
          plugin.logger.warning(
            "[Config] ai.persistent-buffer.ttl-hours=$it is invalid, using $DEFAULT_BUFFER_TTL_HOURS"
          )
        }
      }
    persistentBufferTtlMillis = ttlHours.coerceAtLeast(1L) * MILLIS_PER_HOUR
    persistentBufferCap =
      config.getDouble("ai.persistent-buffer.cap-on-restore", DEFAULT_BUFFER_CAP)
    persistentBufferDecayPerHour =
      config.getDouble("ai.persistent-buffer.decay-rate-per-hour", DEFAULT_BUFFER_DECAY)
    persistentBufferDisconnectWindowMillis =
      config.getLong(
        "ai.persistent-buffer.disconnect-window-seconds",
        DEFAULT_BUFFER_DISCONNECT_WINDOW_SECS,
      ) * MILLIS_PER_SEC
    persistentBufferSaveThreshold =
      config.getDouble("ai.persistent-buffer.save-threshold", DEFAULT_BUFFER_SAVE_THRESHOLD)

    batchEnabled = config.getBoolean("ai.batch.enabled", true)
    batchMaxSize = config.getInt("ai.batch.max-size", DEFAULT_BATCH_MAX_SIZE)
    batchMaxDelayMs = config.getLong("ai.batch.max-delay-ms", DEFAULT_BATCH_MAX_DELAY_MS)

    retryMaxAttempts = config.getInt("ai.retry.max-attempts", DEFAULT_RETRY_MAX_ATTEMPTS)
    retryInitialDelayMs =
      config.getLong("ai.retry.initial-delay-ms", DEFAULT_RETRY_INITIAL_DELAY_MS)
    retryMaxDelayMs = config.getLong("ai.retry.max-delay-ms", DEFAULT_RETRY_MAX_DELAY_MS)
    retryMultiplier = config.getDouble("ai.retry.multiplier", DEFAULT_RETRY_MULTIPLIER)
    retryJitter = config.getDouble("ai.retry.jitter", DEFAULT_RETRY_JITTER)

    val ignoredPatterns = ArrayList<Pattern>()
    for (pattern in config.getStringList("client-brand.ignored-clients")) {
      try {
        ignoredPatterns.add(Pattern.compile(pattern))
      } catch (e: PatternSyntaxException) {
        plugin.logger.warning("[ClientBrand] Invalid regex pattern in config: $pattern")
      }
    }
    ignoredClientPatterns = ignoredPatterns

    disconnectBlacklistedForge =
      config.getBoolean("client-brand.disconnect-blacklisted-forge-versions", true)

    suspiciousAlertsBuffer = config.getDouble("suspicious.alerts.buffer", 25.0)
    cancelDuplicatePacket = config.getBoolean("cancel-duplicate-packet", true)
    forceCancelDuplicatePacket = config.getBoolean("force-cancel-duplicate-packet", false)
    ignoreDuplicatePacketRotation = config.getBoolean("ignore-duplicate-packet-rotation", true)

    debugEnabled = config.getBoolean("debug.enabled", false)
    val enabledCategories = EnumSet.noneOf(DebugCategory::class.java)
    for (category in DebugCategory.values()) {
      if (config.getBoolean("debug.categories.${category.configKey}", false)) {
        enabledCategories.add(category)
      }
    }
    enabledDebugCategories = enabledCategories
  }

  private fun loadDisabledRegions(): Map<String, List<String>> {
    val mapRegions = config.getStringListMap("ai.worldguard.disabled-regions")
    if (mapRegions.isNotEmpty()) {
      return mapRegions
        .mapKeys { it.key.lowercase(Locale.ROOT) }
        .mapValues { entry -> entry.value.map { it.lowercase(Locale.ROOT) } }
    }

    return parseLegacyDisabledRegions()
  }

  private fun parseLegacyDisabledRegions(): Map<String, List<String>> {
    val legacyList = config.getStringList("ai.worldguard.disabled-regions")
    if (legacyList.isEmpty()) return emptyMap()

    plugin.logger.warning(
      "[Config] ai.worldguard.disabled-regions uses deprecated " +
        "region:world format. Please migrate to the new map format."
    )
    val result = mutableMapOf<String, MutableList<String>>()
    for (entry in legacyList) {
      val lower = entry.lowercase(Locale.ROOT)
      if (lower.contains(":")) {
        val parts = lower.split(":", limit = 2)
        val regionName = parts[0]
        val worldName = parts[1]
        result.getOrPut(worldName) { mutableListOf() }.add(regionName)
      } else {
        result.getOrPut("*") { mutableListOf() }.add(lower)
      }
    }
    return result
  }

  fun isClientIgnored(brand: String): Boolean {
    for (pattern in ignoredClientPatterns) {
      if (pattern.matcher(brand).find()) {
        return true
      }
    }
    return false
  }

  private companion object {
    const val MIN_AI_SEQUENCE = 1
    const val MIN_AI_STEP = 1
    const val DEFAULT_AI_SEQUENCE = 40
    const val DEFAULT_AI_STEP = 10
    const val MILLIS_PER_SEC = 1000L
    const val MILLIS_PER_HOUR = 3_600_000L
    const val DEFAULT_BUFFER_TTL_HOURS = 48L
    const val DEFAULT_BUFFER_CAP = 40.0
    const val DEFAULT_BUFFER_DECAY = 2.0
    const val DEFAULT_BUFFER_DISCONNECT_WINDOW_SECS = 300L
    const val DEFAULT_BUFFER_SAVE_THRESHOLD = 1.0

    const val DEFAULT_BATCH_MAX_SIZE = 32
    const val DEFAULT_BATCH_MAX_DELAY_MS = 50L

    const val DEFAULT_RETRY_MAX_ATTEMPTS = 3
    const val DEFAULT_RETRY_INITIAL_DELAY_MS = 500L
    const val DEFAULT_RETRY_MAX_DELAY_MS = 5000L
    const val DEFAULT_RETRY_MULTIPLIER = 2.0
    const val DEFAULT_RETRY_JITTER = 0.25
  }
}
