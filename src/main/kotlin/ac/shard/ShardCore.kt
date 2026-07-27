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
import ac.shard.database.DatabaseManager
import ac.shard.debug.DebugManager
import ac.shard.event.DamageEvent
import ac.shard.monitor.core.ScoreboardSlotObserver
import ac.shard.monitor.hud.MonitorRuntime
import ac.shard.monitor.view.MonitorViewService
import ac.shard.packet.PacketListener
import ac.shard.player.PlayerDataManager
import ac.shard.redis.CrossServerAlertService
import ac.shard.redis.CrossServerSuspiciousService
import ac.shard.redis.RedisManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import ac.shard.telemetry.TelemetryService
import ac.shard.utils.MessageUtil
import com.github.retrooper.packetevents.PacketEvents
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
  private val redisManager: RedisManager,
  private val crossServerAlertService: CrossServerAlertService,
  private val crossServerSuspiciousService: CrossServerSuspiciousService,
  private val debugManager: DebugManager,
  private val packetListener: PacketListener,
  private val monitorViewService: MonitorViewService,
  private val monitorRuntime: MonitorRuntime,
  private val slotObserver: ScoreboardSlotObserver,
  private val damageEvent: DamageEvent,
  private val shardApi: ShardApi,
  private val adventure: BukkitAudiences,
  private val coroutines: ShardCoroutines,
  private val scheduler: SchedulerService,
  private val telemetryService: TelemetryService,
) {
  fun enable() {
    commandManager.registerCommands()

    MessageUtil.init(localeManager, adventure, plugin.logger)

    initializePacketRuntime()
    plugin.server.pluginManager.registerEvents(damageEvent, plugin)
    monitorRuntime.enable()
    plugin.server.servicesManager.register(
      ShardApi::class.java,
      shardApi,
      plugin,
      ServicePriority.Normal,
    )
    scheduler.runAsync {
      crossServerAlertService.start()
      crossServerSuspiciousService.start()
    }
    telemetryService.start()
  }

  fun disable() {
    runCatching { telemetryService.stop() }
    plugin.server.servicesManager.unregister(ShardApi::class.java, shardApi)
    runCatching { playerDataManager.saveAllBuffersSync() }
    runCatching { aiServerProvider.shutdownTransport() }
    runCatching { crossServerAlertService.shutdown() }
    runCatching { crossServerSuspiciousService.shutdown() }
    runCatching { redisManager.shutdown() }
    adventure.close()
    coroutines.close()
    databaseManager.shutdown()
    monitorRuntime.disable()
    runCatching { telemetryService.sendFarewell() }
  }

  fun reload() {
    configManager.reloadConfig()
    localeManager.reload()
    debugManager.reload()
    alertManager.reload()
    aiServerProvider.reload()
    playerDataManager.reloadAllPlayers()
    monitorRuntime.reload()
    monitorViewService.reload()
    crossServerAlertService.shutdown()
    crossServerSuspiciousService.shutdown()
    scheduler.runAsync {
      redisManager.shutdown()
      crossServerAlertService.start()
      crossServerSuspiciousService.start()
    }
  }

  private fun initializePacketRuntime() {
    PacketEvents.getAPI().eventManager.registerListener(packetListener)
    PacketEvents.getAPI().eventManager.registerListener(slotObserver)
    monitorViewService.start()
    PacketEvents.getAPI().init()
  }
}
