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
import ac.shard.player.ShardPlayer
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

private const val STORAGE_DEGRADED_LOG =
  "Persistent database storage failed at runtime. Shard is switching to in-memory storage."

@Suppress("TooManyFunctions")
internal class ResilientViolationDatabase(
  private val primary: ViolationDatabase,
  private val fallback: ViolationDatabase,
  private val health: DatabaseHealth,
  private val logger: Logger,
) : ViolationDatabase {
  private val switchedToFallback = AtomicBoolean(false)

  override fun logAlert(player: ShardPlayer, verbose: String, checkName: String, vls: Int) {
    execute { database -> database.logAlert(player, verbose, checkName, vls) }
  }

  override fun getLogCount(player: UUID): Int {
    return execute { database -> database.getLogCount(player) }
  }

  override fun getViolations(player: UUID, page: Int, limit: Int): List<Violation> {
    return execute { database -> database.getViolations(player, page, limit) }
  }

  override fun getUniqueViolatorsSince(since: Long): Int {
    return execute { database -> database.getUniqueViolatorsSince(since) }
  }

  override fun recordLogin(playerUUID: UUID, timestamp: Long) {
    execute { database -> database.recordLogin(playerUUID, timestamp) }
  }

  override fun countUniquePlayersSince(since: Long): Int {
    return execute { database -> database.countUniquePlayersSince(since) }
  }

  override fun saveAiBuffer(playerUUID: UUID, buffer: Double, updatedAt: Long) {
    execute { database -> database.saveAiBuffer(playerUUID, buffer, updatedAt) }
  }

  override fun loadAiBuffer(playerUUID: UUID): AiBufferState? {
    return execute { database -> database.loadAiBuffer(playerUUID) }
  }

  override fun getLogCount(since: Long): Int {
    return execute { database -> database.getLogCount(since) }
  }

  override fun getLogCounts(playerUUIDs: Collection<UUID>): Map<UUID, Int> {
    return execute { database -> database.getLogCounts(playerUUIDs) }
  }

  override fun getViolations(page: Int, limit: Int, since: Long): List<Violation> {
    return execute { database -> database.getViolations(page, limit, since) }
  }

  override fun getViolationLevel(playerUUID: UUID, punishGroupName: String): Int {
    return execute { database -> database.getViolationLevel(playerUUID, punishGroupName) }
  }

  override fun incrementViolationLevel(playerUUID: UUID, punishGroupName: String): Int {
    return execute { database -> database.incrementViolationLevel(playerUUID, punishGroupName) }
  }

  override fun resetViolationLevel(playerUUID: UUID, punishGroupName: String) {
    execute { database -> database.resetViolationLevel(playerUUID, punishGroupName) }
  }

  override fun resetAllViolationLevels(playerUUID: UUID) {
    execute { database -> database.resetAllViolationLevels(playerUUID) }
  }

  override fun loadMonitorSettings(playerUUID: UUID): MonitorSettings? {
    return execute { database -> database.loadMonitorSettings(playerUUID) }
  }

  override fun saveMonitorSettings(playerUUID: UUID, settings: MonitorSettings) {
    execute { database -> database.saveMonitorSettings(playerUUID, settings) }
  }

  private fun <T> execute(operation: (ViolationDatabase) -> T): T {
    if (!health.isPersistentAvailable()) {
      return operation(fallback)
    }

    return try {
      operation(primary)
    } catch (failure: SQLException) {
      switchToFallback(failure)
      operation(fallback)
    } catch (failure: IllegalStateException) {
      switchToFallback(failure)
      operation(fallback)
    }
  }

  private fun switchToFallback(failure: Throwable) {
    health.markDegraded(failure)
    if (switchedToFallback.compareAndSet(false, true)) {
      logger.log(Level.WARNING, STORAGE_DEGRADED_LOG, failure)
    }
  }
}
