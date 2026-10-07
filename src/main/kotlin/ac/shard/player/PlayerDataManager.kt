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

import ac.shard.alert.AlertManager
import ac.shard.api.impl.Sessions
import ac.shard.data.CollectManager
import ac.shard.integration.GeyserUtil
import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.User
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player

@Suppress("TooManyFunctions")
class PlayerDataManager(
  private val playerFactory: ShardPlayerFactory,
  private val scheduler: SchedulerService,
  private val persistence: PlayerPersistence,
  private val alertManager: AlertManager,
  private val collectManager: CollectManager,
  private val sessions: Sessions,
) {
  private val players = ConcurrentHashMap<User, ShardPlayer>()

  @Suppress("ReturnCount")
  private fun cleanupPlayer(tracked: ShardPlayer) {
    if (!players.remove(tracked.user, tracked)) {
      return
    }
    synchronized(tracked.sessionLock) { tracked.sessionEnded = true }
    tracked.ai.onQuit()
    tracked.playerThreadRefresh?.cancel()
    val uuid = tracked.uuid
    if (players.values.any { it.uuid == uuid }) {
      return
    }
    val player = tracked.playerOrNull
    if (collectManager.getSession(uuid) != null) {
      collectManager.stopCollecting(uuid)
    }
    if (player != null) alertManager.handlePlayerQuit(player)
    if (!tracked.isAttached) return
    sessions.ended(tracked)
    persistence.saveLater(tracked)
  }

  fun saveAllBuffersSync() {
    persistence.saveAll(players.values.filter { it.isAttached })
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
    players[user] = playerFactory.create(user)
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
        shardPlayer.refreshOnPlayerThread()
        shardPlayer.playerThreadRefresh =
          scheduler.runTimer(
            player,
            Runnable { shardPlayer.refreshOnPlayerThread() },
            REFRESH_TICKS,
            REFRESH_TICKS,
          )

        persistence.restore(shardPlayer, player.name) {
          shardPlayer.buffersRestored = true
          sessions.started(shardPlayer)
        }
        alertManager.onJoin(player)
      },
    )
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

  private companion object {
    const val REFRESH_TICKS = 20L
  }
}
