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
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

private const val STORAGE_DEGRADED_LOG =
  "Lost the connection to the database. Shard keeps working in memory and will write the " +
    "pending changes once the database is back."
private const val STORAGE_RESTORED_LOG = "The database is back, %d pending changes were written."

internal fun isConnectionFailure(failure: Throwable): Boolean =
  generateSequence(failure) { it.cause }
    .take(MAX_CAUSE_DEPTH)
    .any { cause ->
      cause is SQLTransientException ||
        cause is SQLRecoverableException ||
        cause is SQLNonTransientConnectionException ||
        (cause is SQLException && isConnectionState(cause))
    }

private fun isConnectionState(failure: SQLException): Boolean {
  val message = failure.message.orEmpty()
  return failure.sqlState?.startsWith("08") == true ||
    "SQLITE_BUSY" in message ||
    "SQLITE_LOCKED" in message ||
    "database is locked" in message
}

private const val MAX_CAUSE_DEPTH = 8

private val strictCalls = ThreadLocal.withInitial { false }

internal fun <T> strictStorage(block: () -> T): T {
  val outer = strictCalls.get()
  strictCalls.set(true)
  try {
    return block()
  } finally {
    strictCalls.set(outer)
  }
}

@Suppress("TooManyFunctions")
internal class ResilientViolationDatabase(
  private val primary: ViolationDatabase,
  private val fallback: ViolationDatabase,
  private val health: DatabaseHealth,
  private val logger: Logger,
  private val maxPending: Int = MAX_PENDING_WRITES,
) : ViolationDatabase {
  private val inFlight = AtomicInteger()
  private val pending = ArrayDeque<(ViolationDatabase) -> Unit>()
  private val reported = ConcurrentHashMap.newKeySet<String>()
  @Volatile private var droppedWrites = 0

  override fun logAlert(violation: Violation) {
    write { it.logAlert(violation) }
  }

  override fun getLogCount(player: UUID): Int = read { it.getLogCount(player) }

  override fun getViolations(player: UUID, page: Int, limit: Int): List<Violation> = read {
    it.getViolations(player, page, limit)
  }

  override fun getUniqueViolatorsSince(since: Long): Int = read {
    it.getUniqueViolatorsSince(since)
  }

  override fun recordLogin(playerUUID: UUID, timestamp: Long) {
    write { it.recordLogin(playerUUID, timestamp) }
  }

  override fun recordPlayer(playerUUID: UUID, name: String, seenAt: Long) {
    write { it.recordPlayer(playerUUID, name, seenAt) }
  }

  override fun findPlayer(playerUUID: UUID): KnownPlayer? = read { it.findPlayer(playerUUID) }

  override fun findPlayersByName(name: String, limit: Int): List<KnownPlayer> = read {
    it.findPlayersByName(name, limit)
  }

  override fun findPlayersByPrefix(prefix: String, limit: Int): List<KnownPlayer> = read {
    it.findPlayersByPrefix(prefix, limit)
  }

  override fun countUniquePlayersSince(since: Long): Int = read {
    it.countUniquePlayersSince(since)
  }

  override fun recordAttack(playerUUID: UUID, timestamp: Long) {
    write { it.recordAttack(playerUUID, timestamp) }
  }

  override fun countAttackersSince(since: Long): Int = read { it.countAttackersSince(since) }

  override fun saveAiBuffer(playerUUID: UUID, buffer: Double, updatedAt: Long) {
    write { it.saveAiBuffer(playerUUID, buffer, updatedAt) }
  }

  override fun loadAiBuffer(playerUUID: UUID): AiBufferState? = read {
    it.loadAiBuffer(playerUUID)
  }

  override fun saveAiLabelBuffers(
    playerUUID: UUID,
    buffers: Map<String, Double>,
    updatedAt: Long,
  ) {
    write { it.saveAiLabelBuffers(playerUUID, buffers, updatedAt) }
  }

  override fun loadAiLabelBuffers(playerUUID: UUID): Map<String, AiBufferState> = read {
    it.loadAiLabelBuffers(playerUUID)
  }

  override fun clearAiLabelBuffers(playerUUID: UUID, match: (String) -> Boolean) {
    write { it.clearAiLabelBuffers(playerUUID, match) }
  }

  override fun saveMitigationScore(playerUUID: UUID, state: StoredScore) {
    write { it.saveMitigationScore(playerUUID, state) }
  }

  override fun clearMitigationScore(playerUUID: UUID, at: Long) {
    write { it.clearMitigationScore(playerUUID, at) }
  }

  override fun loadMitigationScore(playerUUID: UUID): StoredScore? = read {
    it.loadMitigationScore(playerUUID)
  }

  override fun saveAiSnapshot(playerUUID: UUID, snapshot: AiSnapshot) {
    write { it.saveAiSnapshot(playerUUID, snapshot) }
  }

  override fun loadAiSnapshot(playerUUID: UUID): AiSnapshot? = read {
    it.loadAiSnapshot(playerUUID)
  }

  override fun recordMitigation(playerUUID: UUID, entry: MitigationLogEntry) {
    write { it.recordMitigation(playerUUID, entry) }
  }

  override fun getMitigationLog(playerUUID: UUID, limit: Int): List<MitigationLogEntry> = read {
    it.getMitigationLog(playerUUID, limit)
  }

  override fun getMitigationLog(limit: Int): List<MitigationLogEntry> = read {
    it.getMitigationLog(limit)
  }

  override fun getLogCount(since: Long): Int = read { it.getLogCount(since) }

  override fun getLogCounts(playerUUIDs: Collection<UUID>): Map<UUID, Int> = read {
    it.getLogCounts(playerUUIDs)
  }

  override fun getViolations(page: Int, limit: Int, since: Long): List<Violation> = read {
    it.getViolations(page, limit, since)
  }

  override fun getViolationLevel(playerUUID: UUID, punishGroupName: String, since: Long): Int =
    read {
      it.getViolationLevel(playerUUID, punishGroupName, since)
    }

  override fun recordFlag(playerUUID: UUID, punishGroupName: String, at: Long, since: Long): Int =
    write {
      it.recordFlag(playerUUID, punishGroupName, at, since)
    }

  override fun resetViolationLevel(playerUUID: UUID, punishGroupName: String) {
    write { it.resetViolationLevel(playerUUID, punishGroupName) }
  }

  override fun resetAllViolationLevels(playerUUID: UUID) {
    write { it.resetAllViolationLevels(playerUUID) }
  }

  override fun loadMonitorSettings(playerUUID: UUID): MonitorSettings? = read {
    it.loadMonitorSettings(playerUUID)
  }

  override fun saveMonitorSettings(playerUUID: UUID, settings: MonitorSettings) {
    write { it.saveMonitorSettings(playerUUID, settings) }
  }

  val pendingWrites: Int
    get() = synchronized(pending) { pending.size }

  fun recover(): Boolean {
    if (health.isPersistentAvailable()) return true
    val written = replay()
    if (written != null) {
      health.markPersistent()
      logger.info(STORAGE_RESTORED_LOG.format(written))
    }
    return written != null
  }

  fun awaitIdle(timeoutMillis: Long) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(IDLE_POLL_MILLIS)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private fun replay(): Int? {
    var written = 0
    while (true) {
      val next = synchronized(pending) { pending.peekFirst() } ?: return written
      try {
        next(primary)
      } catch (failure: Exception) {
        if (isConnectionFailure(failure)) return null
        report("replay", failure)
      }
      synchronized(pending) { pending.pollFirst() }
      written++
    }
  }

  private fun <T> read(operation: (ViolationDatabase) -> T): T = run(operation, replayable = false)

  private fun <T> write(operation: (ViolationDatabase) -> T): T = run(operation, replayable = true)

  private fun <T> run(operation: (ViolationDatabase) -> T, replayable: Boolean): T {
    inFlight.incrementAndGet()
    try {
      if (!health.isPersistentAvailable()) {
        if (replayable) remember(operation)
        return operation(fallback)
      }
      return try {
        operation(primary)
      } catch (failure: SQLException) {
        recoverFrom(failure, operation, replayable)
      } catch (failure: IllegalStateException) {
        recoverFrom(failure, operation, replayable)
      }
    } finally {
      inFlight.decrementAndGet()
    }
  }

  private fun <T> recoverFrom(
    failure: Exception,
    operation: (ViolationDatabase) -> T,
    replayable: Boolean,
  ): T {
    if (isConnectionFailure(failure)) {
      degrade(failure)
      if (replayable) remember(operation)
    } else {
      report(operation.javaClass.name, failure)
      if (strictCalls.get()) throw failure
    }
    return operation(fallback)
  }

  private fun remember(operation: (ViolationDatabase) -> Any?) {
    synchronized(pending) {
      if (pending.size >= maxPending) {
        pending.pollFirst()
        droppedWrites++
        if (droppedWrites == 1) {
          logger.warning(
            "The database is still unavailable, the oldest pending changes are being dropped."
          )
        }
      }
      pending.addLast { operation(it) }
    }
  }

  private fun degrade(failure: Throwable) {
    if (health.isPersistentAvailable()) {
      logger.log(Level.WARNING, STORAGE_DEGRADED_LOG, failure)
    }
    health.markDegraded(failure)
  }

  private fun report(where: String, failure: Throwable) {
    val key = "$where|${failure.javaClass.name}|${failure.message}"
    if (reported.add(key)) {
      logger.log(Level.WARNING, "A database query failed and was answered from memory.", failure)
    }
  }

  private companion object {
    const val MAX_PENDING_WRITES = 10_000
    const val IDLE_POLL_MILLIS = 10L
  }
}
