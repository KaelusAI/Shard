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
package ac.shard.monitor.view

import ac.shard.Shard
import ac.shard.config.ConfigManager
import ac.shard.monitor.core.ComponentCache
import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.monitor.core.ScoreboardSlotRegistry
import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.PacketEvents
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent

@Suppress("LongParameterList")
class MonitorViewService(
  private val plugin: Shard,
  private val configManager: ConfigManager,
  scheduler: SchedulerService,
  componentCache: ComponentCache,
  sampler: MonitorSampler,
  slotRegistry: ScoreboardSlotRegistry,
  bridge: ScoreboardPacketBridge,
) : Listener {
  private val coordinator =
    ViewSessionCoordinator(plugin, scheduler, componentCache, sampler, slotRegistry, bridge)
  private val trackingObserver =
    ViewTrackingObserver(scheduler, coordinator) { viewerId ->
      resolveActiveViewViewer(viewerId, coordinator)
    }
  private val teamConflictObserver = ViewTeamConflictObserver(coordinator)

  @Volatile private var runtimeConfig = ViewRuntimeConfig.from(configManager.monitorConfig)
  private var packetHooksRegistered = false

  init {
    plugin.server.pluginManager.registerEvents(this, plugin)
  }

  fun start() {
    if (packetHooksRegistered) {
      return
    }
    PacketEvents.getAPI().eventManager.registerListener(trackingObserver)
    PacketEvents.getAPI().eventManager.registerListener(teamConflictObserver)
    packetHooksRegistered = true
  }

  fun toggle(viewer: Player): Boolean {
    val viewerId = viewer.uniqueId
    return if (coordinator.session(viewerId) != null) {
      coordinator.disable(viewerId, viewer)
      false
    } else {
      enable(viewer)
      true
    }
  }

  fun enable(viewer: Player) {
    coordinator.enable(viewer, runtimeConfig)
  }

  fun reload() {
    runtimeConfig = ViewRuntimeConfig.from(configManager.monitorConfig)
    coordinator.reload(runtimeConfig)
  }

  @EventHandler
  fun onPlayerQuit(event: PlayerQuitEvent) {
    val left = event.player
    coordinator.disable(left.uniqueId, left)
    coordinator.removeTargetFromAllSessions(left.uniqueId, left.name)
  }

  @EventHandler
  fun onPluginDisable(event: PluginDisableEvent) {
    if (event.plugin !== plugin) {
      return
    }
    for (viewerId in coordinator.activeViewerIds) {
      coordinator.disable(viewerId, Bukkit.getPlayer(viewerId))
    }
  }
}

internal const val VIEW_PERMISSION = "shard.view"

internal fun isTrackableViewTarget(viewer: Player, target: Player): Boolean {
  return target.isOnline && viewer.world.uid == target.world.uid && viewer.canSee(target)
}

private fun resolveActiveViewViewer(viewerId: UUID, coordinator: ViewSessionCoordinator): Player? {
  val viewer = Bukkit.getPlayer(viewerId)
  return when {
    viewer == null || !viewer.isOnline -> {
      coordinator.disable(viewerId, null)
      null
    }
    !viewer.hasPermission(VIEW_PERMISSION) -> {
      coordinator.disable(viewerId, viewer)
      null
    }
    else -> viewer
  }
}
