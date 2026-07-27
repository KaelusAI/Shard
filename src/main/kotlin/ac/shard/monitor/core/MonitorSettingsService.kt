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
import ac.shard.scheduler.SchedulerService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player

class MonitorSettingsService(
  private val configManager: ConfigManager,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
) {
  private val entries = ConcurrentHashMap<UUID, MonitorSettingsEntry>()

  @Volatile private var snapshot: Snapshot? = null

  private val active: Snapshot
    get() = snapshot ?: readSnapshot().also { snapshot = it }

  fun defaults(): MonitorSettings = active.defaults

  fun reload() {
    val previous = snapshot
    val next = readSnapshot()
    snapshot = next
    if (previous != null && previous.perPlayer != next.perPlayer) {
      entries.clear()
    }
  }

  fun prewarm(uuid: UUID) {
    val config = active
    val fresh = MonitorSettingsEntry(config.defaults, loaded = !config.perPlayer)
    val existing = entries.putIfAbsent(uuid, fresh)
    if (existing != null || !config.perPlayer || !config.prewarmOnJoin) {
      return
    }
    scheduler.runAsync {
      val stored = loadFromDatabase(uuid)
      entries.computeIfPresent(uuid) { _, entry ->
        if (entry === fresh) {
          entry.publishLoaded(stored)
        }
        entry
      }
    }
  }

  fun getSettings(uuid: UUID): MonitorSettings = entries[uuid]?.settings ?: defaults()

  fun mutate(
    viewer: Player,
    mutator: (MonitorSettings) -> MonitorSettings,
    onApplied: (MonitorSettings) -> Unit,
  ) {
    val uuid = viewer.uniqueId
    val config = active
    val entry =
      entries.computeIfAbsent(uuid) {
        MonitorSettingsEntry(config.defaults, loaded = !config.perPlayer)
      }
    if (entry.isLoaded) {
      onApplied(applyAndPersist(uuid, entry, mutator))
      return
    }
    scheduler.runAsync {
      val updated = applyAndPersist(uuid, entry, mutator)
      scheduler.runSync(viewer) { onApplied(updated) }
    }
  }

  fun evict(uuid: UUID) {
    entries.remove(uuid)
  }

  private fun applyAndPersist(
    uuid: UUID,
    entry: MonitorSettingsEntry,
    mutator: (MonitorSettings) -> MonitorSettings,
  ): MonitorSettings {
    val updated = entry.apply({ loadFromDatabase(uuid) }, mutator)
    if (active.perPlayer) {
      persist(uuid, entry)
    }
    return updated
  }

  private fun persist(uuid: UUID, entry: MonitorSettingsEntry) {
    val claimed = entry.claimWriter() ?: return
    scheduler.runAsync {
      var pending = claimed
      var running = true
      while (running) {
        databaseManager.database.saveMonitorSettings(uuid, pending)
        val next = entry.nextWrite(pending)
        if (next == null) {
          running = false
        } else {
          pending = next
        }
      }
    }
  }

  private fun loadFromDatabase(uuid: UUID): MonitorSettings? =
    databaseManager.database.loadMonitorSettings(uuid)

  private fun readSnapshot(): Snapshot {
    val config = configManager.monitorConfig
    return Snapshot(
      defaults =
        MonitorSettings(
          mode = MonitorMode.fromConfig(config.getString("defaults.mode", "compact")),
          theme = MonitorTheme.fromConfig(config.getString("defaults.theme", "calm")),
          showPing = config.getBoolean("defaults.show-ping", true),
          showDmg = config.getBoolean("defaults.show-dmg", true),
          showTrend = config.getBoolean("defaults.show-trend", true),
          showName = MonitorNameMode.fromConfig(config.getString("defaults.show-name", "auto")),
          outputs = MonitorOutputKind.parseSet(config.getString("defaults.output", "actionbar")),
          chatStyle = MonitorChatStyle.fromConfig(config.getString("defaults.chat-style", "live")),
        ),
      perPlayer = config.getBoolean("storage.per-player", true),
      prewarmOnJoin = config.getBoolean("storage.prewarm-on-join", true),
    )
  }

  private class Snapshot(
    val defaults: MonitorSettings,
    val perPlayer: Boolean,
    val prewarmOnJoin: Boolean,
  )
}
