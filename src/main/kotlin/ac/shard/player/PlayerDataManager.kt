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
import ac.shard.mitigation.MitigationLogStore
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.punishment.PunishmentManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.User
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player

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
  private val mitigationScoreStore: MitigationScoreStore,
  private val mitigationLogStore: MitigationLogStore,
) {
  private val players = ConcurrentHashMap<User, ShardPlayer>()
  private val unsavedBuffers = ConcurrentHashMap.newKeySet<ShardPlayer>()

  @Suppress("ReturnCount")
  private fun cleanupPlayer(tracked: ShardPlayer) {
    if (!players.remove(tracked.user, tracked)) {
      return
    }
    val uuid = tracked.uuid
    if (players.values.any { it.uuid == uuid }) {
      return
    }
    val player = tracked.playerOrNull
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
    if (!tracked.isAttached) return
    // runAsync only starts on the next heartbeat, so the write is not guaranteed by shutdown.
    unsavedBuffers.add(tracked)
    scheduler.runAsync {
      if (unsavedBuffers.remove(tracked)) {
        persistentBufferService.saveOnQuit(tracked)
        mitigationScoreStore.save(tracked)
        mitigationLogStore.saveOnQuit(tracked)
      }
    }
  }

  fun saveAllBuffersSync() {
    for (shardPlayer in players.values) {
      if (shardPlayer.isAttached) {
        persistentBufferService.saveOnShutdown(shardPlayer)
      }
    }
    val iterator = unsavedBuffers.iterator()
    while (iterator.hasNext()) {
      val shardPlayer = iterator.next()
      iterator.remove()
      persistentBufferService.saveOnShutdown(shardPlayer)
      mitigationScoreStore.save(shardPlayer)
    }
  }

  fun getPlayer(player: Player?): ShardPlayer? {
    if (player == null) {
      return null
    }
    return getPlayer(player.uniqueId)
  }

  fun getPlayer(uuid: UUID): ShardPlayer? {
    val byChannel = resolveUser(uuid)?.let { players[it] }
    val tracked = byChannel ?: players.values.firstOrNull { it.uuid == uuid }
    return tracked?.takeIf { it.isAttached }
  }

  fun getPlayer(user: User?): ShardPlayer? {
    if (user == null) {
      return null
    }
    return players[user]
  }

  private fun resolveUser(uuid: UUID): User? =
    runCatching {
        val protocol = PacketEvents.getAPI().protocolManager
        val channel = protocol.getChannel(uuid) ?: return@runCatching null
        protocol.getUser(channel)
      }
      .getOrNull()

  fun handleUserConnect(user: User) {
    if (user.uuid == null || players.containsKey(user)) {
      return
    }
    players[user] =
      ShardPlayer(
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
  }

  fun getPlayers(): Collection<ShardPlayer> {
    return players.values.filter { it.isAttached }
  }

  fun getSessions(): Collection<ShardPlayer> {
    return players.values.toList()
  }

  fun handleUserLogin(user: User, player: Player) {
    scheduler.runSync(
      player,
      Runnable {
        if (!player.isOnline) {
          return@Runnable
        }
        handleUserConnect(user)
        val shardPlayer = players[user] ?: return@Runnable
        if (shardPlayer.isAttached) {
          return@Runnable
        }
        val playerUuid = player.uniqueId
        shardPlayer.entityId = user.entityId.takeIf { it > 0 } ?: player.entityId
        applyInitialWorldState(shardPlayer, player)
        shardPlayer.isBedrock = GeyserUtil.isBedrockPlayer(playerUuid)

        if (players[user] !== shardPlayer) {
          return@Runnable
        }
        shardPlayer.attach(player)

        val loginTimestamp = System.currentTimeMillis()
        scheduler.runAsync { databaseManager.database.recordLogin(playerUuid, loginTimestamp) }
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

  private fun applyInitialWorldState(shardPlayer: ShardPlayer, player: Player) {
    val gameMode =
      com.github.retrooper.packetevents.protocol.player.GameMode.getById(player.gameMode.value)
    if (gameMode != null) {
      shardPlayer.gameMode = gameMode
      shardPlayer.tracking.gameMode = gameMode.ordinal
    }
    shardPlayer.compensatedWorld.updateMinHeight(player.world.minHeight)
  }

  fun handleUserDisconnect(user: User) {
    val tracked = players[user] ?: return
    cleanupPlayer(tracked)
  }

  fun reloadAllPlayers() {
    for (shardPlayer in players.values) {
      shardPlayer.reload()
    }
  }
}
