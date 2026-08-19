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
package ac.shard.player

import ac.shard.Shard
import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.event.ShardEventBus
import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.DataCollectorManager
import ac.shard.checks.impl.ai.PersistentBufferService
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.integration.GeyserUtil
import ac.shard.mitigation.MitigationLogStore
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.punishment.PunishmentManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

class PlayerDataManager
@Suppress("LongParameterList")
constructor(
  private val plugin: Shard,
  private val alertManager: AlertManager,
  private val dataCollectorManager: DataCollectorManager,
  private val configManager: ConfigManager,
  private val aiServerProvider: AIServerProvider,
  private val exemptManager: ExemptManager,
  private val scheduler: SchedulerService,
  private val checkManagerFactory: CheckManager.Factory,
  private val punishmentManagerFactory: PunishmentManager.Factory,
  private val eventBus: ShardEventBus,
  private val databaseManager: DatabaseManager,
  private val persistentBufferService: PersistentBufferService,
  private val mitigationScoreStore: MitigationScoreStore,
  private val mitigationLogStore: MitigationLogStore,
) : Listener {
  private val players = ConcurrentHashMap<UUID, ShardPlayer>()

  init {
    plugin.server.pluginManager.registerEvents(this, plugin)
  }

  @EventHandler
  fun onQuit(event: PlayerQuitEvent) {
    cleanupPlayer(event.player.uniqueId, event.player)
  }

  private fun cleanupPlayer(uuid: UUID, player: Player?) {
    if (dataCollectorManager.getSession(uuid) != null) {
      dataCollectorManager.stopCollecting(uuid)
    }
    if (player != null) {
      runCatching { alertManager.handlePlayerQuit(player) }
        .onFailure {
          plugin.logger.log(
            java.util.logging.Level.WARNING,
            "alertManager.handlePlayerQuit failed for ${player.name}",
            it,
          )
        }
    }
    val tracked = players.remove(uuid) ?: return
    scheduler.runAsync {
      persistentBufferService.saveOnQuit(tracked)
      mitigationScoreStore.save(tracked)
      mitigationLogStore.saveOnQuit(tracked)
    }
  }

  fun saveAllBuffersSync() {
    for (shardPlayer in players.values) {
      persistentBufferService.saveOnShutdown(shardPlayer)
      mitigationScoreStore.save(shardPlayer)
    }
  }

  fun getPlayer(player: Player?): ShardPlayer? {
    if (player == null) {
      return null
    }
    return players[player.uniqueId]
  }

  fun getPlayer(uuid: UUID): ShardPlayer? {
    return players[uuid]
  }

  fun getPlayers(): Collection<ShardPlayer> {
    return players.values
  }

  fun handleUserLogin(
    user: com.github.retrooper.packetevents.protocol.player.User,
    player: Player,
  ) {
    scheduler.runSync(
      player,
      Runnable {
        if (!player.isOnline || players.containsKey(player.uniqueId)) {
          return@Runnable
        }

        val loginTimestamp = System.currentTimeMillis()
        val playerUuid = player.uniqueId
        scheduler.runAsync { databaseManager.database.recordLogin(playerUuid, loginTimestamp) }

        val shardPlayer =
          ShardPlayer(
            player = player,
            user = user,
            plugin = plugin,
            configManager = configManager,
            aiSequence = configManager.aiSequence,
            alertManager = alertManager,
            dataCollectorManager = dataCollectorManager,
            aiServerProvider = aiServerProvider,
            exemptManager = exemptManager,
            scheduler = scheduler,
            checkManagerFactory = checkManagerFactory,
            punishmentManagerFactory = punishmentManagerFactory,
            eventBus = eventBus,
          )
        shardPlayer.isBedrock = GeyserUtil.isBedrockPlayer(playerUuid)
        players[player.uniqueId] = shardPlayer
        persistentBufferService.restoreOnLogin(shardPlayer)
        mitigationScoreStore.restoreOnLogin(shardPlayer)

        enableAlertsOnJoin(player)
      },
    )
  }

  private fun enableAlertsOnJoin(player: Player) {
    AlertType.entries.forEach { type ->
      if (
        player.hasPermission(type.permission) &&
          player.hasPermission("${type.permission}.enable-on-join") &&
          !alertManager.hasAlertsEnabled(player, type)
      ) {
        alertManager.toggle(player, type, true)
      }
    }
  }

  fun handleUserDisconnect(user: com.github.retrooper.packetevents.protocol.player.User) {
    val uuid = user.uuid ?: return
    cleanupPlayer(uuid, players[uuid]?.player)
  }

  fun reloadAllPlayers() {
    for (shardPlayer in players.values) {
      shardPlayer.reload()
    }
  }
}
