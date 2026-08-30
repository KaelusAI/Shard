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
import ac.shard.ai.label.LabelCatalog
import ac.shard.ai.label.LabelKey
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelThresholds
import ac.shard.config.yaml.YamlPatcher
import ac.shard.connect.CredentialsStore
import ac.shard.data.TickData
import ac.shard.data.TickSchema
import ac.shard.debug.DebugCategory
import ac.shard.mitigation.MitigationSettings
import ac.shard.region.RegionCheckMode
import java.io.File
import java.util.EnumSet
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.CRC32
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import ru.vyarus.yaml.updater.YamlUpdater

@Suppress("LargeClass")
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
  var aiPreWindow: Int = 0
    private set

  @Volatile
  var aiPostWindow: Int = 0
    private set

  @Volatile
  var aiStep: Int = 0
    private set

  @Volatile
  var aiModel: String? = null
    private set

  @Volatile private var aiPanelModelName: String = ""

  var aiModelTitle: String = ""
    private set

  @Volatile
  var aiColumns: List<String>? = null
    private set

  @Volatile
  var aiLabels: List<String> = emptyList()
    private set

  @Volatile
  var aiColumnMask: LongArray = TickSchema.fullMask
    private set

  @Volatile
  var aiLabelNames: Map<String, String> = emptyMap()
    private set

  @Volatile
  var aiServerLabelNames: Map<String, String> = emptyMap()
    private set

  val labelCatalog = LabelCatalog(local = { aiLabelNames }, fromServer = { aiServerLabelNames })

  @Volatile
  var aiLabelMode: LabelMode? = null
    private set

  @Volatile
  var aiServerLabelMode: LabelMode? = null
    private set

  val effectiveLabelMode: LabelMode?
    get() = aiLabelMode ?: aiServerLabelMode

  @Volatile
  var aiLabelSplit: Boolean = true
    private set

  @Volatile
  var aiLabelMaxTracked: Int = DEFAULT_MAX_TRACKED_LABELS
    private set

  @Volatile
  var aiLegitLabels: Set<String> = emptySet()
    private set

  @Volatile
  var aiLabelThresholds: Map<String, LabelThresholds> = emptyMap()
    private set

  @Volatile private var fingerprint: String = ""

  var aiGzipEnabled: Boolean = true
    private set

  var collectPreWindow: Int = 0
    private set

  var collectPostWindow: Int = 0
    private set

  var aiFlag: Double = 0.0
    private set

  var aiResetOnFlag: Double = 0.0
    private set

  var aiBufferMultiplier: Double = 0.0
    private set

  var aiBufferDecrease: Double = 0.0
    private set

  var editorConsoleOnly: Boolean = false
    private set

  var editorConsoleForCommands: Boolean = false
    private set

  var mitigationsConfig: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  @Volatile
  var mitigationSettings: MitigationSettings = MitigationsFile.OFF
    private set

  private var aiWorldGuardEnabled = false

  var aiWorldGuardFlagOverridesList: Boolean = true
    private set

  var aiDisabledRegions: Map<String, List<String>> = emptyMap()
    private set

  var regionCheckMode: RegionCheckMode = RegionCheckMode.SKIP_DETECTION
    private set

  private var bedrockExemptEnabled = false

  @Volatile
  var enabledWindowStarts: Int = 1 shl TickData.START_MELEE_PLAYER.toInt()
    private set

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

  var enabledDebugCategories: Set<DebugCategory> = emptySet()
    private set

  init {
    loadConfigs()
  }

  fun reloadConfig() {
    loadConfigs()
  }

  @Synchronized
  @Suppress("LongParameterList")
  fun updateAiParams(
    preWindow: Int?,
    postWindow: Int?,
    step: Int?,
    model: String? = null,
    columns: List<String>? = null,
    labels: List<String>? = null,
    labelNames: Map<String, String>? = null,
    legitLabels: List<String>? = null,
    modelTitle: String? = null,
    labelMode: String? = null,
    labelThresholds: Map<String, Map<String, Double>>? = null,
  ): Boolean {
    var changed = columns != null && columns != aiColumns && applyAiColumns(columns)
    changed = applyLabelParams(labels, labelNames, legitLabels, modelTitle) || changed
    changed = applyLabelMode(labelMode) || changed
    if (labelThresholds != null) {
      val parsed = parseThresholds(labelThresholds)
      if (parsed != aiLabelThresholds) {
        plugin.logger.info("[Config] AI label thresholds -> ${parsed.keys} (from server)")
        aiLabelThresholds = parsed
        changed = true
      }
    }
    changed = applyAiWindow(preWindow, postWindow, step) || changed
    if (!model.isNullOrBlank() && model != aiModel) {
      plugin.logger.info("[Config] AI model $aiModel -> $model (from server)")
      aiModel = model
      changed = true
    }
    if (changed) {
      fingerprint = computeFingerprint()
      plugin.logger.info("[Config] Model config now: ${describeModelConfig()}")
      modelStore.write(
        aiPreWindow,
        aiPostWindow,
        aiStep,
        aiModel,
        aiColumns,
        aiLabels,
        aiServerLabelNames,
        aiLegitLabels,
        aiModelTitle,
        aiServerLabelMode?.wire,
        aiLabelThresholds,
      )
    }
    return changed
  }

  private fun applyLabelMode(labelMode: String?): Boolean {
    val parsed = labelMode?.let { LabelMode.fromConfig(it) }
    val unreadable = labelMode != null && parsed == null && labelMode.isNotBlank()
    if (unreadable) {
      plugin.logger.warning("[Config] Inference server sent an unknown label mode $labelMode")
    }
    val moves = labelMode != null && !unreadable && parsed != aiServerLabelMode
    if (moves) {
      plugin.logger.info(
        "[Config] AI label mode $aiServerLabelMode -> ${parsed?.wire ?: "none"} (from server)"
      )
      aiServerLabelMode = parsed
    }
    return moves
  }

  private fun applyLabelParams(
    labels: List<String>?,
    labelNames: Map<String, String>?,
    legitLabels: List<String>?,
    modelTitle: String?,
  ): Boolean {
    var changed = false
    if (labels != null) {
      val canonical = canonicalLabels(labels)
      if (canonical != aiLabels) {
        plugin.logger.info("[Config] AI labels $aiLabels -> $canonical (from server)")
        aiLabels = canonical
        changed = true
      }
    }
    if (labelNames != null) {
      val canonical =
        labelNames
          .mapNotNull { (key, name) ->
            val label = LabelKey.canonical(key) ?: return@mapNotNull null
            LabelKey.title(name)?.let { label to it }
          }
          .toMap()
      if (canonical != aiServerLabelNames) {
        aiServerLabelNames = canonical
        changed = true
      }
    }
    if (legitLabels != null) {
      val canonical = legitLabels.mapNotNull(LabelKey::canonical).toSet()
      if (canonical != aiLegitLabels) {
        aiLegitLabels = canonical
        changed = true
      }
    }
    if (modelTitle != null && modelTitle != aiModelTitle) {
      aiModelTitle = modelTitle
      changed = true
    }
    return changed
  }

  fun notePanelModelName(name: String) {
    aiPanelModelName = name
  }

  fun modelTitle(): String =
    aiModelTitle.ifBlank { aiPanelModelName }.ifBlank { aiModel.orEmpty() }.ifBlank { "-" }

  fun describeModelConfig(): String = buildString {
    append("model=").append(aiModel ?: "-")
    append(" window=").append(aiPreWindow).append('/').append(aiPostWindow)
    append(" step=").append(aiStep)
    append(" columns=").append(aiColumns?.size ?: TickSchema.columnCount(aiColumnMask))
    append(" labels=").append(if (aiLabels.isEmpty()) "-" else aiLabels.joinToString(","))
    append(" mode=").append(effectiveLabelMode?.wire ?: "-")
    append(" titles=").append(aiServerLabelNames.size)
    append(" legit=").append(if (aiLegitLabels.isEmpty()) "-" else aiLegitLabels.joinToString(","))
    append(" fingerprint=").append(modelConfigFingerprint())
  }

  fun modelConfigFingerprint(): String = fingerprint

  private fun computeFingerprint(): String {
    val canonical =
      buildString {
          appendLine("pre_window=$aiPreWindow")
          appendLine("post_window=$aiPostWindow")
          appendLine("step=$aiStep")
          appendLine("model=${aiModel.orEmpty()}")
          appendLine("labels=${aiLabels.joinToString(",")}")
          appendLine("legit_labels=${aiLegitLabels.sorted().joinToString(",")}")
          appendLine("label_mode=${aiServerLabelMode?.wire.orEmpty()}")
          append(
            "label_thresholds=" +
              aiLabelThresholds.toSortedMap().entries.joinToString(",") {
                "${it.key}:${it.value.cheat}:${it.value.legit}"
              }
          )
        }
        .toByteArray(Charsets.UTF_8)
    val crc = CRC32()
    crc.update(canonical)
    return "%08x".format(crc.value)
  }

  private fun parseThresholds(raw: Map<String, Map<String, Double>>) =
    raw
      .mapNotNull { (key, values) ->
        val label = LabelKey.canonical(key) ?: return@mapNotNull null
        LabelThresholds.parse(values["cheat"], values["legit"])?.let { label to it }
      }
      .toMap()

  private fun canonicalLabels(raw: List<String>): List<String> {
    val dropped = mutableListOf<String>()
    val canonical = LabelKey.canonicalList(raw) { value, key -> dropped += "$value -> $key" }
    if (dropped.isNotEmpty()) {
      plugin.logger.warning(
        "[Config] Inference server sent labels that collapse into one key: " +
          dropped.joinToString(", ")
      )
    }
    val lost = raw.size - canonical.size - dropped.size
    if (lost > 0) {
      plugin.logger.warning("[Config] Dropped $lost unusable label name(s) from the server")
    }
    return canonical
  }

  private fun applyAiWindow(preWindow: Int?, postWindow: Int?, step: Int?): Boolean {
    var changed = false
    if (preWindow != null && preWindow >= MIN_AI_WINDOW && preWindow != aiPreWindow) {
      plugin.logger.info("[Config] AI pre-window $aiPreWindow -> $preWindow (from server)")
      aiPreWindow = preWindow
      changed = true
    }
    if (postWindow != null && postWindow >= MIN_AI_WINDOW && postWindow != aiPostWindow) {
      plugin.logger.info("[Config] AI post-window $aiPostWindow -> $postWindow (from server)")
      aiPostWindow = postWindow
      changed = true
    }
    if (step != null && step >= MIN_AI_STEP && step != aiStep) {
      plugin.logger.info("[Config] AI step $aiStep -> $step (from server)")
      aiStep = step
      changed = true
    }
    return changed
  }

  private fun loadNegotiatedAiParams() {
    aiPreWindow =
      modelStore.readPreWindow()
        ?: config
          .getInt("ai.inference.pre-window", DEFAULT_AI_PRE_WINDOW)
          .coerceAtLeast(MIN_AI_WINDOW)
    aiPostWindow =
      modelStore.readPostWindow()
        ?: config
          .getInt("ai.inference.post-window", DEFAULT_AI_POST_WINDOW)
          .coerceAtLeast(MIN_AI_WINDOW)
    aiStep =
      modelStore.readStep()
        ?: config.getInt("ai.inference.step", DEFAULT_AI_STEP).coerceAtLeast(MIN_AI_STEP)
    aiModel = modelStore.readModel()
    aiModelTitle = modelStore.readModelTitle()
    aiServerLabelMode = LabelMode.fromConfig(modelStore.readLabelMode())
    aiLabels = canonicalLabels(modelStore.readLabels().orEmpty())
    aiServerLabelNames =
      modelStore
        .readLabelNames()
        .mapNotNull { (key, name) -> LabelKey.canonical(key)?.let { it to name } }
        .toMap()
    aiLegitLabels = modelStore.readLegitLabels().mapNotNull(LabelKey::canonical).toSet()
    aiLabelThresholds =
      modelStore
        .readLabelThresholds()
        .mapNotNull { (key, value) -> LabelKey.canonical(key)?.let { it to value } }
        .toMap()
    modelStore.readColumns()?.let { applyAiColumns(it) }
    fingerprint = computeFingerprint()
  }

  private fun applyAiColumns(columns: List<String>): Boolean {
    val mask = TickSchema.maskOf(columns)
    if (mask == null) {
      val unknown = columns.filterNot { it in TickSchema.fieldNames }
      plugin.logger.warning(
        "[Config] Inference server asked for unknown columns $unknown; sending every column instead"
      )
      aiColumns = null
      aiColumnMask = TickSchema.fullMask
      return false
    }
    plugin.logger.info(
      "[Config] AI columns ${TickSchema.columnCount(aiColumnMask)} -> ${columns.size} (from server)"
    )
    aiColumns = columns
    aiColumnMask = mask
    return true
  }

  fun isAiEnabled(): Boolean = aiEnabled

  fun isTelemetryEnabled(): Boolean = telemetryEnabled

  fun isAiWorldGuardEnabled(): Boolean = aiWorldGuardEnabled

  fun isBedrockExemptEnabled(): Boolean = bedrockExemptEnabled

  fun isDisconnectBlacklistedForge(): Boolean = disconnectBlacklistedForge

  private fun loadConfigs() {
    if (!plugin.dataFolder.exists()) {
      plugin.dataFolder.mkdirs()
    }

    config = loadConfig("config.yml", config, migrate = true)
    punishments = loadConfig("punishments.yml", punishments)
    monitorConfig = loadConfig("monitor.yml", monitorConfig, migrate = true)
    mitigationsConfig = loadConfig("mitigations.yml", mitigationsConfig, migrate = true)

    loadValues()
  }

  private fun loadConfig(
    fileName: String,
    previous: ConfigView,
    migrate: Boolean = false,
  ): ConfigView {
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
      keepPrevious(fileName, e, previous)
    }
  }

  private fun keepPrevious(fileName: String, error: Exception, previous: ConfigView): ConfigView {
    plugin.logger.severe("[Config] $fileName could not be parsed: ${error.message}")
    if (previous.root().empty()) {
      plugin.logger.severe(
        "[Config] Shard is running on built-in defaults, which leave the AI check off. " +
          "Fix $fileName and reload."
      )
    } else {
      plugin.logger.severe("[Config] Keeping the values loaded before this reload.")
    }
    return previous
  }

  private fun present(file: File, path: String): Boolean =
    runCatching { YamlPatcher.has(YamlPatcher.read(file), path) }.getOrDefault(true)

  private fun runMigration(file: File, fileName: String) {
    val updateStream = javaClass.classLoader.getResourceAsStream(fileName) ?: return

    val currentVersion = ConfigMigrations.readVersion(file, fileName)
    if (fileName == "config.yml" && renameCrossServerToNetwork(file)) {
      plugin.logger.info("[Config] Moved cross-server settings into the network section")
    }
    val drops =
      ConfigMigrations.forcedDropsForUpgradeFrom(currentVersion, fileName, file).filter {
        it == "config-version" || present(file, it)
      }

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

  @Suppress("CyclomaticComplexMethod", "LongMethod")
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
    loadNegotiatedAiParams()
    aiLabelNames =
      config
        .getStringMap("ai.labels.names")
        .mapNotNull { (key, name) ->
          LabelKey.canonical(key)?.let { it to name }
        }
        .toMap()
    aiLabelMode = LabelMode.fromConfig(config.getString("ai.labels.mode", "auto"))
    aiLabelSplit =
      when (config.getString("ai.labels.split", "auto").trim().lowercase(Locale.ROOT)) {
        "never",
        "false" -> false
        else -> true
      }
    aiLabelMaxTracked =
      config.getInt("ai.labels.max-tracked", DEFAULT_MAX_TRACKED_LABELS).coerceAtLeast(1)
    aiGzipEnabled = config.getBoolean("ai.gzip", true)
    collectPreWindow = config.getInt("ai.collect.pre-window", DEFAULT_COLLECT_WINDOW)
    collectPostWindow = config.getInt("ai.collect.post-window", DEFAULT_COLLECT_WINDOW)

    aiFlag = config.getDouble("ai.buffer.flag", 50.0)
    aiResetOnFlag = config.getDouble("ai.buffer.reset-on-flag", 25.0)
    aiBufferMultiplier = config.getDouble("ai.buffer.multiplier", 100.0)
    aiBufferDecrease = config.getDouble("ai.buffer.decrease", 1.0)

    editorConsoleOnly = config.getBoolean("editor.console-only", false)
    editorConsoleForCommands = config.getBoolean("editor.console-for-commands", false)

    val complaints = mutableListOf<String>()
    mitigationSettings = MitigationsFile.read(mitigationsConfig.root(), complaints)
    complaints.forEach { plugin.logger.warning("[Mitigations] $it") }

    aiWorldGuardEnabled = config.getBoolean("ai.worldguard.enabled", true)
    aiWorldGuardFlagOverridesList = config.getBoolean("ai.worldguard.flag-overrides-list", true)
    aiDisabledRegions = loadDisabledRegions()
    regionCheckMode =
      RegionCheckMode.fromConfig(config.getString("ai.worldguard.mode", "skip-detection"))

    bedrockExemptEnabled = config.getBoolean("exemptions.bedrock", true)

    enabledWindowStarts = loadWindowStarts()

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

    val enabledCategories = EnumSet.noneOf(DebugCategory::class.java)
    for (category in DebugCategory.values()) {
      if (config.getBoolean("debug.categories.${category.configKey}", false)) {
        enabledCategories.add(category)
      }
    }
    enabledDebugCategories = enabledCategories
  }

  private fun loadWindowStarts(): Int {
    var mask = 1 shl TickData.START_MELEE_PLAYER.toInt()
    for ((key, kind) in OPTIONAL_WINDOW_STARTS) {
      if (config.getBoolean("experimental.extra-window-starts.$key", false))
        mask = mask or (1 shl kind)
    }
    return mask
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
    private val OPTIONAL_WINDOW_STARTS =
      listOf(
        "melee-living-other" to TickData.START_MELEE_LIVING_OTHER.toInt(),
        "attack-end-crystal" to TickData.START_ATTACK_END_CRYSTAL.toInt(),
        "attack-entity-other" to TickData.START_ATTACK_ENTITY_OTHER.toInt(),
        "use-respawn-anchor" to TickData.START_USE_RESPAWN_ANCHOR.toInt(),
        "place-end-crystal" to TickData.START_PLACE_END_CRYSTAL.toInt(),
        "explosion-received" to TickData.START_EXPLOSION_RECEIVED.toInt(),
      )

    const val DEFAULT_MAX_TRACKED_LABELS = 32

    const val MIN_AI_WINDOW = 1
    const val MIN_AI_STEP = 1
    const val DEFAULT_AI_PRE_WINDOW = 32
    const val DEFAULT_AI_POST_WINDOW = 32
    const val DEFAULT_AI_STEP = 32
    const val DEFAULT_COLLECT_WINDOW = 128
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
