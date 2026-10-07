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
import ac.shard.ai.stream.StreamProfile
import ac.shard.config.yaml.YamlPatcher
import ac.shard.connect.CredentialsStore
import ac.shard.data.TickData
import ac.shard.mitigation.MitigationSettings
import ac.shard.punishment.PunishmentTree
import ac.shard.punishment.PunishmentTreeParser
import ac.shard.punishment.PunishmentsMigration
import java.io.File
import java.util.concurrent.Executors
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import ru.vyarus.yaml.updater.YamlUpdater

class ConfigManager(private val plugin: Shard, private val credentialsStore: CredentialsStore) {
  @Volatile
  var config: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  @Volatile private var punishments: ConfigView = ConfigView(CommentedConfigurationNode.root())

  @Volatile
  var punishmentTree: PunishmentTree = PunishmentTree.EMPTY
    private set

  @Volatile
  var monitorConfig: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  @Volatile
  var settings: ShardSettings = ShardSettings.DEFAULT
    private set

  @Volatile private var aiPanelModelName: String = ""

  val labelCatalog =
    LabelCatalog(
      local = { settings.localAi.names },
      fromServer = { streamProfile?.primary?.labelTitles.orEmpty() },
      profile = { streamProfile },
    )

  val profiles =
    ProfileStore(
      plugin.logger,
      plugin.dataFolder.toPath().resolve(PROFILE_FILE),
      { settings.localAi },
      Executors.newSingleThreadExecutor { Thread(it, "Shard-Profile").apply { isDaemon = true } },
    )

  val streamProfile: StreamProfile?
    get() = profiles.current

  val enabledWindowStarts: Int
    get() = streamProfile?.windowStartMask ?: (1 shl TickData.START_MELEE_PLAYER.toInt())

  val suspiciousAlertsBuffer: Double
    get() =
      streamProfile?.primary?.let { it.role.alert?.buffer ?: it.buffer.resetOnFlag }
        ?: SUSPICIOUS_DEFAULT

  @Volatile
  var mitigationsConfig: ConfigView = ConfigView(CommentedConfigurationNode.root())
    private set

  @Volatile
  var mitigationSettings: MitigationSettings = MitigationsFile.OFF
    private set

  init {
    reloadConfig()
  }

  @Synchronized
  fun reloadConfig() {
    if (!plugin.dataFolder.exists()) {
      plugin.dataFolder.mkdirs()
    }

    config = loadConfig("config.yml", config, migrate = true)
    migratePunishments(File(plugin.dataFolder, PUNISHMENTS_FILE))
    reloadPunishments()
    monitorConfig = loadConfig("monitor.yml", monitorConfig, migrate = true)
    mitigationsConfig = loadConfig("mitigations.yml", mitigationsConfig, migrate = true)

    settings =
      ShardSettings.read(config, credentialsStore.read(), settings.localAi.generation + 1) {
        plugin.logger.warning(it)
      }
    val complaints = mutableListOf<String>()
    mitigationSettings = MitigationsFile.read(mitigationsConfig.root(), complaints)
    complaints.forEach { plugin.logger.warning("[Mitigations] $it") }
    profiles.renarrow()
    profiles.loadStored()
  }

  fun notePanelModelName(name: String) {
    aiPanelModelName = name
  }

  fun modelTitle(): String =
    streamProfile?.active?.joinToString(", ") { it.title } ?: aiPanelModelName.ifBlank { "-" }

  fun describeModelConfig(): String {
    val profile = streamProfile ?: return "profile=none"
    return buildString {
      append("profile=").append(profile.configHeader)
      append(" models=")
      append(
        profile.active.joinToString(",") { m ->
          val labels = if (m.labels.isEmpty()) "-" else m.labels.joinToString("+")
          "${m.id}[${m.cadence}/${m.span} $labels]"
        }
      )
      append(" primary=").append(profile.primary.id)
    }
  }

  fun modelConfigFingerprint(): String = streamProfile?.let { "%08x".format(it.crc) }.orEmpty()

  fun declaredDetections(): List<String> {
    val profile = streamProfile ?: return emptyList()
    return profile.active.flatMap { m ->
      m.labels
        .filterNot { it in m.legitLabels }
        .map { if (m.id == profile.primary.id) it else "${m.id}/$it" }
    }
  }

  fun punishmentsFile(): File = File(plugin.dataFolder, PUNISHMENTS_FILE)

  @Synchronized
  fun reloadPunishments() {
    punishments = loadConfig(PUNISHMENTS_FILE, punishments)
    punishmentTree =
      PunishmentTreeParser.parse(punishments.root()).also { tree ->
        tree.problems.forEach { plugin.logger.warning("[Punish] $PUNISHMENTS_FILE: $it") }
      }
  }

  private fun migratePunishments(file: File) {
    if (!file.isFile) return
    runCatching {
        val root = YamlConfigurationLoader.builder().path(file.toPath()).build().load()
        if (!PunishmentsMigration.isLegacy(root)) return
        val backup =
          generateSequence(1) { it + 1 }
            .map { File(plugin.dataFolder, PunishmentsMigration.backupName(it)) }
            .first { !it.exists() }
        file.copyTo(backup)
        file.writeText(PunishmentsMigration.convert(root, backup.name))
        val groups = PunishmentsMigration.groupCount(root)
        if (groups > 1) {
          plugin.logger.warning(
            "[Punish] $PUNISHMENTS_FILE: $groups groups were converted, a flag now counts in one " +
              "group only. Check /shard punishments"
          )
        }
      }
      .onFailure {
        plugin.logger.warning("[Punish] could not convert $PUNISHMENTS_FILE: ${it.message}")
      }
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
    if (fileName == "config.yml" && renameLegacyNetworkSection(file)) {
      plugin.logger.info("[Config] Moved the old cross-server settings into the network section")
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

  private companion object {
    const val PROFILE_FILE = "profile.json"
    const val PUNISHMENTS_FILE = "punishments.yml"
    const val SUSPICIOUS_DEFAULT = 25.0
  }
}
