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

import ac.shard.api.impl.ShardImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.command.CommandManager
import ac.shard.data.CollectManager
import ac.shard.data.CollectSession
import ac.shard.database.DatabaseManager
import ac.shard.http.ShardHttp
import ac.shard.mitigation.MitigationRuntime
import ac.shard.monitor.MonitorServices
import ac.shard.network.NetworkServices
import ac.shard.packet.PacketListener
import ac.shard.packet.PacketSendListener
import ac.shard.player.ExemptManager
import ac.shard.player.PlayerDataManager
import ac.shard.player.PlayerThreadRefreshListener
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import ac.shard.telemetry.TelemetryService
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import java.util.logging.Level
import java.util.logging.Logger
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import org.bukkit.plugin.ServicePriority

class ShardCore
@Suppress("LongParameterList")
constructor(
  private val plugin: Shard,
  private val playerDataManager: PlayerDataManager,
  private val aiServerProvider: AIServerProvider,
  private val commandManager: CommandManager,
  private val databaseManager: DatabaseManager,
  private val network: NetworkServices,
  private val packetEvents: PacketEventsLoader,
  private val packetListener: PacketListener,
  private val sendListener: PacketSendListener,
  private val monitor: MonitorServices,
  private val mitigationRuntime: MitigationRuntime,
  private val shardApi: ShardImpl,
  private val events: ShardEvents,
  private val exemptions: ExemptManager,
  private val adventure: BukkitAudiences,
  private val scheduler: SchedulerService,
  private val telemetryService: TelemetryService,
  private val collectManager: CollectManager,
  private val http: ShardHttp,
  private val logger: Logger,
  private val refreshListener: PlayerThreadRefreshListener,
) {
  fun enable() {
    databaseManager.start()
    aiServerProvider.start()
    plugin.server.servicesManager.register(
      ac.shard.api.Shard::class.java,
      shardApi,
      plugin,
      ServicePriority.Normal,
    )
    plugin.server.pluginManager.registerEvents(events, plugin)
    plugin.server.pluginManager.registerEvents(exemptions, plugin)
    scheduler.runTimerAsync({ exemptions.sweep() }, SWEEP_MILLIS, SWEEP_MILLIS)
    CollectSession.sweepStaging(plugin.dataFolder)
    commandManager.registerCommands()
    initializePacketRuntime()
    plugin.server.pluginManager.registerEvents(refreshListener, plugin)
    mitigationRuntime.enable()
    monitor.runtime.enable()
    scheduler.runAsync { network.start() }
    telemetryService.start()
    commandManager.startCommands()
  }

  fun disable() {
    runCatching { PacketEvents.getAPI().eventManager.unregisterListener(packetListener) }
    runCatching { PacketEvents.getAPI().eventManager.unregisterListener(sendListener) }
    runCatching { PacketEvents.getAPI().eventManager.unregisterListener(monitor.slotObserver) }
    runCatching { monitor.runtime.disable() }
    // Saves must finish before the scheduler stops running queued work.
    runCatching { collectManager.saveAll() }
    runCatching { playerDataManager.saveAllBuffersSync() }
    runCatching { scheduler.cancelTasks() }
    runCatching { telemetryService.stop() }
    runCatching { events.shutdown() }
    runCatching { shardApi.close() }
    runCatching {
      plugin.server.servicesManager.unregister(ac.shard.api.Shard::class.java, shardApi)
    }
    runCatching { mitigationRuntime.disable() }
    runCatching { aiServerProvider.stop() }
    runCatching { network.shutdown() }
    runCatching { adventure.close() }
    runCatching { databaseManager.stop() }
    runCatching { telemetryService.sendFarewell() }
    runCatching { http.close() }
  }

  private fun initializePacketRuntime() {
    PacketEvents.getAPI().eventManager.registerListener(packetListener)
    PacketEvents.getAPI().eventManager.registerListener(sendListener)
    PacketEvents.getAPI().eventManager.registerListener(monitor.slotObserver)
    monitor.view.start()
    packetEvents.init()
    trackAlreadyOnlinePlayers()
    scheduler.runTimer({ pollAllPlayers() }, 1L, 1L)
  }

  private fun trackAlreadyOnlinePlayers() {
    for (player in plugin.server.onlinePlayers) {
      val user = runCatching { PacketEvents.getAPI().playerManager.getUser(player) }.getOrNull()
      if (user == null) {
        logger.warning("No PacketEvents user for ${player.name}; leaving them untracked")
        continue
      }
      playerDataManager.handleUserLogin(user, player)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun pollAllPlayers() {
    for (shardPlayer in playerDataManager.getSessions()) {
      try {
        if (!ChannelHelper.isOpen(shardPlayer.user.channel)) {
          playerDataManager.handleUserDisconnect(shardPlayer.user)
        } else if (shardPlayer.isAttached) {
          shardPlayer.pollData()
        }
      } catch (e: Exception) {
        logger.log(Level.WARNING, "Polling ${shardPlayer.name} failed", e)
      }
    }
  }

  private companion object {
    const val SWEEP_MILLIS = 1_000L
  }
}
