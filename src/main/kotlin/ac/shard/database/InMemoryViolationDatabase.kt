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

import ac.shard.monitor.core.MonitorSettings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_IN_MEMORY_VIOLATIONS = 5000
private const val MAX_IN_MEMORY_MITIGATIONS = 500

@Suppress("TooManyFunctions")
internal class InMemoryViolationDatabase : ViolationDatabase {
  private val monitorSettings = ConcurrentHashMap<UUID, MonitorSettings>()
  private val punishmentLevels = ConcurrentHashMap<PunishmentKey, List<Long>>()
  private val playerLogins = ConcurrentHashMap<UUID, Long>()
  private val players = ConcurrentHashMap<UUID, KnownPlayer>()
  private val playerAttacks = ConcurrentHashMap<UUID, Long>()
  private val aiBuffers = ConcurrentHashMap<UUID, AiBufferState>()
  private val aiLabelBuffers = ConcurrentHashMap<UUID, ConcurrentHashMap<String, AiBufferState>>()
  private val mitigationScores = ConcurrentHashMap<UUID, StoredScore>()
  private val aiSnapshots = ConcurrentHashMap<UUID, AiSnapshot>()
  private val mitigationLog = ArrayDeque<Pair<UUID, MitigationLogEntry>>()
  private val mitigationLogLock = Any()
  private val violations = ArrayDeque<Violation>()
  private val violationsLock = Any()

  override fun logAlert(violation: Violation) {
    synchronized(violationsLock) {
      violations.addFirst(violation)
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

  override fun recordPlayer(playerUUID: UUID, name: String, seenAt: Long) {
    players[playerUUID] = KnownPlayer(playerUUID, name, seenAt)
  }

  override fun findPlayer(playerUUID: UUID): KnownPlayer? = players[playerUUID]

  override fun findPlayersByName(name: String, limit: Int): List<KnownPlayer> =
    players.values
      .filter { it.name.equals(name, ignoreCase = true) }
      .sortedByDescending { it.lastSeen }
      .take(limit)

  override fun findPlayersByPrefix(prefix: String, limit: Int): List<KnownPlayer> =
    players.values
      .filter { it.name.startsWith(prefix, ignoreCase = true) }
      .sortedByDescending { it.lastSeen }
      .take(limit)

  override fun countUniquePlayersSince(since: Long): Int {
    return playerLogins.values.count { it >= since }
  }

  override fun recordAttack(playerUUID: UUID, timestamp: Long) {
    playerLogins[playerUUID] = timestamp
    playerAttacks[playerUUID] = timestamp
  }

  override fun countAttackersSince(since: Long): Int = playerAttacks.values.count { it >= since }

  override fun saveAiBuffer(playerUUID: UUID, buffer: Double, updatedAt: Long) {
    aiBuffers[playerUUID] = AiBufferState(buffer, updatedAt)
  }

  override fun loadAiBuffer(playerUUID: UUID): AiBufferState? = aiBuffers[playerUUID]

  override fun saveAiLabelBuffers(
    playerUUID: UUID,
    buffers: Map<String, Double>,
    updatedAt: Long,
  ) {
    if (buffers.isEmpty()) {
      aiLabelBuffers.remove(playerUUID)
      return
    }
    val stored = aiLabelBuffers.getOrPut(playerUUID) { ConcurrentHashMap() }
    stored.keys.retainAll(buffers.keys)
    for ((label, value) in buffers) {
      stored[label] = AiBufferState(value, updatedAt)
    }
  }

  override fun loadAiLabelBuffers(playerUUID: UUID): Map<String, AiBufferState> =
    aiLabelBuffers[playerUUID]?.toMap() ?: emptyMap()

  override fun clearAiLabelBuffers(playerUUID: UUID, match: (String) -> Boolean) {
    val stored = aiLabelBuffers[playerUUID]
    stored?.keys?.removeIf(match)
    if (!stored.isNullOrEmpty()) return
    aiLabelBuffers.remove(playerUUID)
    aiBuffers.computeIfPresent(playerUUID) { _, _ -> AiBufferState(0.0, 0L) }
  }

  override fun saveMitigationScore(playerUUID: UUID, state: StoredScore) {
    mitigationScores[playerUUID] = state
  }

  override fun clearMitigationScore(playerUUID: UUID, at: Long) {
    mitigationScores.computeIfPresent(playerUUID) { _, stored ->
      stored.copy(score = 0.0, updatedAt = at)
    }
  }

  override fun loadMitigationScore(playerUUID: UUID): StoredScore? = mitigationScores[playerUUID]

  override fun saveAiSnapshot(playerUUID: UUID, snapshot: AiSnapshot) {
    aiSnapshots[playerUUID] = snapshot
  }

  override fun loadAiSnapshot(playerUUID: UUID): AiSnapshot? = aiSnapshots[playerUUID]

  override fun recordMitigation(playerUUID: UUID, entry: MitigationLogEntry) {
    synchronized(mitigationLogLock) {
      mitigationLog.addFirst(playerUUID to entry)
      while (mitigationLog.size > MAX_IN_MEMORY_MITIGATIONS) {
        mitigationLog.removeLast()
      }
    }
  }

  override fun getMitigationLog(playerUUID: UUID, limit: Int): List<MitigationLogEntry> =
    snapshotMitigationLog()
      .asSequence()
      .filter { (uuid, _) -> uuid == playerUUID }
      .map { (uuid, entry) -> entry.copy(playerUUID = uuid) }
      .sortedByDescending { it.endedAt }
      .take(limit)
      .toList()

  override fun getMitigationLog(limit: Int): List<MitigationLogEntry> =
    snapshotMitigationLog()
      .map { (uuid, entry) -> entry.copy(playerUUID = uuid) }
      .sortedByDescending { it.endedAt }
      .take(limit)

  private fun snapshotMitigationLog(): List<Pair<UUID, MitigationLogEntry>> =
    synchronized(mitigationLogLock) { mitigationLog.toList() }

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

  override fun getViolationLevel(playerUUID: UUID, punishGroupName: String, since: Long): Int =
    punishmentLevels[PunishmentKey(playerUUID, punishGroupName)]?.count { it >= since } ?: 0

  override fun recordFlag(playerUUID: UUID, punishGroupName: String, at: Long, since: Long): Int {
    val key = PunishmentKey(playerUUID, punishGroupName)
    val kept =
      punishmentLevels.compute(key) { _, flags -> flags.orEmpty().filter { it >= since } + at }
    return kept?.size ?: 0
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
    val skip = ((safePage - 1).toLong() * safeLimit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return drop(skip).take(safeLimit).toList()
  }

  private data class PunishmentKey(val playerUUID: UUID, val punishGroupName: String)
}
