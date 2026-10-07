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
package ac.shard

import ac.shard.ai.label.VerdictResolver
import ac.shard.alert.AlertManager
import ac.shard.api.event.config.ConfigurationArea
import ac.shard.api.impl.event.ConfigurationChangeEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationRuntime
import ac.shard.monitor.MonitorServices
import ac.shard.network.NetworkServices
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider

@Suppress("LongParameterList")
class ShardReloader(
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val debugManager: DebugManager,
  private val alertManager: AlertManager,
  private val aiServerProvider: AIServerProvider,
  private val mitigationRuntime: MitigationRuntime,
  private val monitor: MonitorServices,
  private val network: NetworkServices,
  private val scheduler: SchedulerService,
  private val events: ShardEvents,
) {
  fun reload() {
    configManager.reloadConfig()
    VerdictResolver.forgetReports()
    localeManager.reload()
    debugManager.reload()
    alertManager.reload()
    aiServerProvider.reload()
    mitigationRuntime.reload()
    monitor.runtime.reload()
    monitor.view.reload()
    network.stopMirrors()
    scheduler.runAsync { network.restart() }
    events.publish(ConfigurationChangeEventImpl(ConfigurationArea.entries.toSet()))
  }

  fun reloadConnection() {
    configManager.reloadConfig()
    aiServerProvider.reload()
  }
}
