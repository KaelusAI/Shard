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

import ac.shard.alert.AlertManager
import ac.shard.api.ShardApi
import ac.shard.command.CommandManager
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.coroutines.ShardCoroutines
import ac.shard.data.CollectManager
import ac.shard.data.CollectSession
import ac.shard.database.DatabaseManager
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationRuntime
import ac.shard.monitor.MonitorServices
import ac.shard.packet.PacketListener
import ac.shard.player.PlayerDataManager
import ac.shard.redis.CrossServerServices
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import ac.shard.telemetry.TelemetryService
import ac.shard.utils.MessageUtil
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
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val aiServerProvider: AIServerProvider,
  private val commandManager: CommandManager,
  private val alertManager: AlertManager,
  private val databaseManager: DatabaseManager,
  private val crossServer: CrossServerServices,
  private val debugManager: DebugManager,
  private val packetListener: PacketListener,
  private val monitor: MonitorServices,
  private val mitigationRuntime: MitigationRuntime,
  private val shardApi: ShardApi,
  private val adventure: BukkitAudiences,
  private val coroutines: ShardCoroutines,
  private val scheduler: SchedulerService,
  private val telemetryService: TelemetryService,
  private val collectManager: CollectManager,
  private val logger: Logger,
) {
  fun enable() {
    CollectSession.sweepStaging(plugin.dataFolder)
    commandManager.registerCommands()

    MessageUtil.init(localeManager, adventure, plugin.logger)

    initializePacketRuntime()
    mitigationRuntime.enable()
    monitor.runtime.enable()
    plugin.server.servicesManager.register(
      ShardApi::class.java,
      shardApi,
      plugin,
      ServicePriority.Normal,
    )
    scheduler.runAsync { crossServer.start() }
    telemetryService.start()
  }

  fun disable() {
    runCatching { PacketEvents.getAPI().eventManager.unregisterListener(packetListener) }
    // Saves must finish before the scheduler stops running queued work.
    runCatching { collectManager.saveAll() }
    runCatching { playerDataManager.saveAllBuffersSync() }
    runCatching { scheduler.cancelTasks() }
    runCatching { telemetryService.stop() }
    plugin.server.servicesManager.unregister(ShardApi::class.java, shardApi)
    runCatching { mitigationRuntime.disable() }
    runCatching { aiServerProvider.shutdownTransport() }
    runCatching { crossServer.shutdown() }
    adventure.close()
    coroutines.close()
    databaseManager.shutdown()
    monitor.runtime.disable()
    runCatching { telemetryService.sendFarewell() }
  }

  fun reload() {
    configManager.reloadConfig()
    localeManager.reload()
    debugManager.reload()
    alertManager.reload()
    aiServerProvider.reload()
    playerDataManager.reloadAllPlayers()
    mitigationRuntime.reload()
    monitor.runtime.reload()
    monitor.view.reload()
    crossServer.stopMirrors()
    scheduler.runAsync {
      crossServer.redis.shutdown()
      crossServer.start()
    }
  }

  private fun initializePacketRuntime() {
    PacketEvents.getAPI().eventManager.registerListener(packetListener)
    PacketEvents.getAPI().eventManager.registerListener(monitor.slotObserver)
    monitor.view.start()
    PacketEvents.getAPI().init()
    trackAlreadyOnlinePlayers()
    scheduler.runTimer({ pollAllPlayers() }, 0L, 1L)
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
}
