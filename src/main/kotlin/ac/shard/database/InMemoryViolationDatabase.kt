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
package ac.shard.database

import ac.shard.config.ConfigManager
import ac.shard.monitor.core.MonitorSettings
import ac.shard.player.ShardPlayer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_IN_MEMORY_VIOLATIONS = 5000

@Suppress("TooManyFunctions")
internal class InMemoryViolationDatabase(private val configManager: ConfigManager) :
  ViolationDatabase {
  private val monitorSettings = ConcurrentHashMap<UUID, MonitorSettings>()
  private val punishmentLevels = ConcurrentHashMap<PunishmentKey, Int>()
  private val playerLogins = ConcurrentHashMap<UUID, Long>()
  private val aiBuffers = ConcurrentHashMap<UUID, AiBufferState>()
  private val violations = ArrayDeque<Violation>()
  private val violationsLock = Any()

  override fun logAlert(player: ShardPlayer, verbose: String, checkName: String, vls: Int) {
    val entry =
      Violation(
        serverName = configManager.config.getString("history.server-name", "server"),
        playerUUID = player.uuid,
        playerName = player.player.name,
        checkName = checkName,
        verbose = verbose,
        vl = vls,
        createdAt = Instant.now(),
      )

    synchronized(violationsLock) {
      violations.addFirst(entry)
      while (violations.size > MAX_IN_MEMORY_VIOLATIONS) {
        violations.removeLast()
      }
    }
  }

  override fun getLogCount(player: UUID): Int {
    return snapshotViolations().count { violation -> violation.playerUUID == player }
  }

  override fun getViolations(player: UUID, page: Int, limit: Int): List<Violation> {
    return snapshotViolations()
      .asSequence()
      .filter { violation -> violation.playerUUID == player }
      .page(page, limit)
  }

  override fun getUniqueViolatorsSince(since: Long): Int {
    return snapshotViolations()
      .asSequence()
      .filter { violation -> violation.createdAt.toEpochMilli() >= since }
      .map(Violation::playerUUID)
      .distinct()
      .count()
  }

  override fun recordLogin(playerUUID: UUID, timestamp: Long) {
    playerLogins[playerUUID] = timestamp
  }

  override fun countUniquePlayersSince(since: Long): Int {
    return playerLogins.values.count { it >= since }
  }

  override fun saveAiBuffer(playerUUID: UUID, buffer: Double, updatedAt: Long) {
    aiBuffers[playerUUID] = AiBufferState(buffer, updatedAt)
  }

  override fun loadAiBuffer(playerUUID: UUID): AiBufferState? = aiBuffers[playerUUID]

  override fun getLogCount(since: Long): Int {
    return snapshotViolations().count { violation -> violation.createdAt.toEpochMilli() >= since }
  }

  override fun getLogCounts(playerUUIDs: Collection<UUID>): Map<UUID, Int> {
    if (playerUUIDs.isEmpty()) {
      return emptyMap()
    }

    val requestedPlayers = playerUUIDs.toSet()
    return snapshotViolations()
      .asSequence()
      .filter { violation -> violation.playerUUID in requestedPlayers }
      .groupingBy(Violation::playerUUID)
      .eachCount()
  }

  override fun getViolations(page: Int, limit: Int, since: Long): List<Violation> {
    return snapshotViolations()
      .asSequence()
      .filter { violation -> violation.createdAt.toEpochMilli() >= since }
      .page(page, limit)
  }

  override fun getViolationLevel(playerUUID: UUID, punishGroupName: String): Int {
    return punishmentLevels[PunishmentKey(playerUUID, punishGroupName)] ?: 0
  }

  override fun incrementViolationLevel(playerUUID: UUID, punishGroupName: String): Int {
    val key = PunishmentKey(playerUUID, punishGroupName)
    return punishmentLevels.compute(key) { _, currentLevel -> (currentLevel ?: 0) + 1 } ?: 0
  }

  override fun resetViolationLevel(playerUUID: UUID, punishGroupName: String) {
    punishmentLevels.remove(PunishmentKey(playerUUID, punishGroupName))
  }

  override fun resetAllViolationLevels(playerUUID: UUID) {
    punishmentLevels.keys.removeIf { key -> key.playerUUID == playerUUID }
  }

  override fun loadMonitorSettings(playerUUID: UUID): MonitorSettings? {
    return monitorSettings[playerUUID]
  }

  override fun saveMonitorSettings(playerUUID: UUID, settings: MonitorSettings) {
    monitorSettings[playerUUID] = settings
  }

  private fun snapshotViolations(): List<Violation> {
    synchronized(violationsLock) {
      return violations.toList()
    }
  }

  private fun Sequence<Violation>.page(page: Int, limit: Int): List<Violation> {
    val safePage = page.coerceAtLeast(1)
    val safeLimit = limit.coerceAtLeast(1)
    return drop((safePage - 1) * safeLimit).take(safeLimit).toList()
  }

  private data class PunishmentKey(val playerUUID: UUID, val punishGroupName: String)
}
