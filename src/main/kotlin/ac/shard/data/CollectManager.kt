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
package ac.shard.data

import ac.shard.Shard
import ac.shard.config.ConfigManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class CollectManager(
  private val plugin: Shard,
  private val configManager: ConfigManager,
  private val scheduler: SchedulerService,
) {
  val activeSessions: MutableMap<UUID, CollectSession> = ConcurrentHashMap()
  private val windows: MutableMap<UUID, AttackWindowTracker> = ConcurrentHashMap()
  private val unsavedSessions: MutableMap<UUID, CollectSession> = ConcurrentHashMap()

  fun startCollecting(shardPlayer: ShardPlayer, label: String): Boolean {
    val uuid = shardPlayer.uuid
    if (activeSessions.containsKey(uuid)) return false
    shardPlayer.tracking.pendingSequenceBreak = true
    shardPlayer.tracking.pendingBufferReset = true

    activeSessions[uuid] =
      CollectSession(
        sessionId = UUID.randomUUID(),
        playerUuid = uuid,
        playerName = shardPlayer.player.name,
        label = label,
        serverVersion = plugin.server.version,
        clientVersion = shardPlayer.user.clientVersion.protocolVersion,
      )
    windows[uuid] = AttackWindowTracker()
    return true
  }

  fun stopCollecting(uuid: UUID): Boolean {
    val session = removeSession(uuid) ?: return false
    // runAsync only starts on the next heartbeat, so the write is not guaranteed by shutdown.
    unsavedSessions[session.sessionId] = session
    scheduler.runAsync {
      if (unsavedSessions.remove(session.sessionId, session)) {
        writeSession(session)
      }
    }
    return true
  }

  private fun writeSession(session: CollectSession) {
    try {
      session.save(plugin.dataFolder)
    } catch (e: IOException) {
      plugin.logger.log(Level.SEVERE, "Failed to save data for ${session.playerName}", e)
    }
  }

  fun cancelCollecting(uuid: UUID): Boolean = removeSession(uuid) != null

  fun getSession(uuid: UUID): CollectSession? = activeSessions[uuid]

  fun saveAll() {
    val uuids = activeSessions.keys.toList()
    for (uuid in uuids) {
      val session = removeSession(uuid) ?: continue
      writeSession(session)
    }
    for ((id, session) in unsavedSessions) {
      if (unsavedSessions.remove(id, session)) {
        writeSession(session)
      }
    }
  }

  fun getCurrentProgress(uuid: UUID): Int = windows[uuid]?.ticksSinceAttack ?: -1

  fun getPostWindow(): Int = configManager.collectPostWindow

  fun onTick(shardPlayer: ShardPlayer) {
    val uuid = shardPlayer.uuid
    val session = activeSessions[uuid]
    if (session == null) {
      windows.remove(uuid)
      return
    }

    val tracker = windows.getOrPut(uuid) { AttackWindowTracker() }
    tracker.onTick(
      shardPlayer.tickBuffer,
      shardPlayer.tracking.windowStartThisTick,
      shardPlayer.tracking.windowStartKind,
      configManager.collectPostWindow,
    ) { ticks, attackIndex, kind ->
      val window =
        shardPlayer.tickBuffer.extractWindow(
          configManager.collectPreWindow,
          configManager.collectPostWindow,
          ticks,
          attackIndex,
          kind,
        )
      if (window != null) session.addWindow(window)
    }
  }

  private fun removeSession(uuid: UUID): CollectSession? {
    val session = activeSessions.remove(uuid) ?: return null
    windows.remove(uuid)
    return session
  }
}
