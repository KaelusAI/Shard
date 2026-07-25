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
package ac.shard.monitor.core

import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.database.ViolationDatabase
import ac.shard.scheduler.SchedulerService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MonitorSettingsService(
  private val configManager: ConfigManager,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
) {
  private val cache = ConcurrentHashMap<UUID, MonitorSettings>()

  fun getSettings(uuid: UUID): MonitorSettings {
    return cache.computeIfAbsent(uuid) { loadSettings(it) }
  }

  fun invalidate(uuid: UUID) {
    cache.remove(uuid)
  }

  fun invalidateAll() {
    cache.clear()
  }

  fun updateSettings(uuid: UUID, settings: MonitorSettings) {
    cache[uuid] = settings
    saveAsync(uuid, settings)
  }

  fun defaultSettings(): MonitorSettings {
    val config = configManager.monitorConfig
    val mode = MonitorMode.fromConfig(config.getString("defaults.mode", "compact"))
    val theme = MonitorTheme.fromConfig(config.getString("defaults.theme", "calm"))
    val showPing = config.getBoolean("defaults.show-ping", true)
    val showDmg = config.getBoolean("defaults.show-dmg", true)
    val showTrend = config.getBoolean("defaults.show-trend", true)
    val showName = MonitorNameMode.fromConfig(config.getString("defaults.show-name", "auto"))
    val output = MonitorOutputKind.fromConfig(config.getString("defaults.output", "actionbar"))
    val chatStyle = MonitorChatStyle.fromConfig(config.getString("defaults.chat-style", "digest"))
    return MonitorSettings(mode, theme, showPing, showDmg, showTrend, showName, output, chatStyle)
  }

  private fun loadSettings(uuid: UUID): MonitorSettings {
    val perPlayer = configManager.monitorConfig.getBoolean("storage.per-player", true)
    val database: ViolationDatabase = databaseManager.database
    if (!perPlayer) {
      return defaultSettings()
    }

    val settings = database.loadMonitorSettings(uuid)
    return settings ?: defaultSettings()
  }

  private fun saveAsync(uuid: UUID, settings: MonitorSettings) {
    val perPlayer = configManager.monitorConfig.getBoolean("storage.per-player", true)
    val database: ViolationDatabase = databaseManager.database
    if (!perPlayer) {
      return
    }

    scheduler.runAsync { database.saveMonitorSettings(uuid, settings) }
  }
}
