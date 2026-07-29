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
import ac.shard.checks.impl.ai.PersistentBufferService
import ac.shard.config.ConfigManager
import ac.shard.data.CollectManager
import ac.shard.database.DatabaseManager
import ac.shard.integration.GeyserUtil
import ac.shard.punishment.PunishmentManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

@Suppress("TooManyFunctions")
class PlayerDataManager
@Suppress("LongParameterList")
constructor(
  private val plugin: Shard,
  private val alertManager: AlertManager,
  private val collectManager: CollectManager,
  private val configManager: ConfigManager,
  private val aiServerProvider: AIServerProvider,
  private val exemptManager: ExemptManager,
  private val scheduler: SchedulerService,
  private val checkManagerFactory: CheckManager.Factory,
  private val punishmentManagerFactory: PunishmentManager.Factory,
  private val eventBus: ShardEventBus,
  private val databaseManager: DatabaseManager,
  private val persistentBufferService: PersistentBufferService,
) : Listener {
  private val players = ConcurrentHashMap<UUID, ShardPlayer>()
  private val unsavedBuffers = ConcurrentHashMap.newKeySet<ShardPlayer>()

  init {
    plugin.server.pluginManager.registerEvents(this, plugin)
  }

  @EventHandler
  fun onQuit(event: PlayerQuitEvent) {
    cleanupPlayer(event.player.uniqueId, event.player, players[event.player.uniqueId])
  }

  private fun cleanupPlayer(uuid: UUID, player: Player?, tracked: ShardPlayer?) {
    // A late close of an old channel must not evict the session that replaced it.
    val removed = tracked != null && players.remove(uuid, tracked)
    if (tracked != null && !removed) {
      return
    }
    if (collectManager.getSession(uuid) != null) {
      collectManager.stopCollecting(uuid)
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
    if (tracked == null) return
    // runAsync only starts on the next heartbeat, so the write is not guaranteed by shutdown.
    unsavedBuffers.add(tracked)
    scheduler.runAsync {
      if (unsavedBuffers.remove(tracked)) {
        persistentBufferService.saveOnQuit(tracked)
      }
    }
  }

  fun saveAllBuffersSync() {
    for (shardPlayer in players.values) {
      persistentBufferService.saveOnShutdown(shardPlayer)
    }
    val iterator = unsavedBuffers.iterator()
    while (iterator.hasNext()) {
      val shardPlayer = iterator.next()
      iterator.remove()
      persistentBufferService.saveOnShutdown(shardPlayer)
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
        if (!player.isOnline) {
          return@Runnable
        }
        val existing = players[player.uniqueId]
        if (existing != null) {
          if (existing.user === user) {
            return@Runnable
          }
          cleanupPlayer(player.uniqueId, existing.player, existing)
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
            alertManager = alertManager,
            aiServerProvider = aiServerProvider,
            exemptManager = exemptManager,
            scheduler = scheduler,
            checkManagerFactory = checkManagerFactory,
            punishmentManagerFactory = punishmentManagerFactory,
            eventBus = eventBus,
          )
        shardPlayer.entityId = user.entityId.takeIf { it > 0 } ?: player.entityId
        applyInitialWorldState(shardPlayer, player)
        shardPlayer.isBedrock = GeyserUtil.isBedrockPlayer(playerUuid)
        players[player.uniqueId] = shardPlayer
        persistentBufferService.restoreOnLogin(shardPlayer)

        enableAlertsOnJoin(player, "shard.alerts", AlertType.REGULAR)
        enableAlertsOnJoin(player, "shard.brand", AlertType.BRAND)
        enableAlertsOnJoin(player, "shard.suspicious.alerts", AlertType.SUSPICIOUS)
      },
    )
  }

  private fun enableAlertsOnJoin(player: Player, permission: String, type: AlertType) {
    if (!player.hasPermission(permission) || !player.hasPermission("$permission.enable-on-join")) {
      return
    }
    if (!alertManager.hasAlertsEnabled(player, type)) {
      alertManager.toggle(player, type, true)
    }
  }

  private fun applyInitialWorldState(shardPlayer: ShardPlayer, player: Player) {
    val gameMode =
      com.github.retrooper.packetevents.protocol.player.GameMode.getById(player.gameMode.value)
    if (gameMode != null) {
      shardPlayer.gameMode = gameMode
      shardPlayer.tracking.gameMode = gameMode.ordinal
    }
    shardPlayer.compensatedWorld.updateMinHeight(player.world.minHeight)
  }

  fun handleUserDisconnect(user: com.github.retrooper.packetevents.protocol.player.User) {
    val uuid = user.uuid ?: return
    val tracked = players[uuid]
    if (tracked != null && tracked.user !== user) {
      return
    }
    cleanupPlayer(uuid, tracked?.player, tracked)
  }

  fun reloadAllPlayers() {
    for (shardPlayer in players.values) {
      shardPlayer.reload()
    }
  }
}
