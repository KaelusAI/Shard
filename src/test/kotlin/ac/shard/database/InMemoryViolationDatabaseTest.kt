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

import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class InMemoryViolationDatabaseTest {

  @Test
  fun `stores alerts in memory with working counts paging and time filters`() {
    val database = InMemoryViolationDatabase()
    val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    database.logAlert(violation(first, "Alpha", "first", at = 1_000L))
    database.logAlert(violation(second, "Bravo", "second", at = 2_000L))

    assertEquals(1, database.getLogCount(first))
    assertEquals(1, database.getLogCount(second))
    assertEquals(2, database.getLogCount(0))
    assertEquals(1, database.getLogCount(1_001L))
    assertEquals(2, database.getUniqueViolatorsSince(0))
    assertEquals(1, database.getUniqueViolatorsSince(1_001L))
    assertEquals(1, database.getLogCounts(listOf(first, second))[first])
    assertEquals(
      listOf("second", "first"),
      database.getViolations(page = 1, limit = 10, since = 0).map(Violation::verbose),
    )
    assertEquals(
      listOf("first"),
      database.getViolations(first, page = 1, limit = 10).map(Violation::verbose),
    )
    assertEquals(
      java.time.Instant.ofEpochMilli(1_000L),
      database.getViolations(first, page = 1, limit = 1).single().createdAt,
    )
  }

  @Test
  fun `stores punishment levels and monitor settings in degraded runtime`() {
    val database = InMemoryViolationDatabase()
    val playerId = UUID.randomUUID()
    val settings =
      MonitorSettings(
        mode = MonitorMode.COMPACT,
        theme = MonitorTheme.CALM,
        showPing = true,
        showDmg = true,
        showTrend = false,
        showCollect = true,
        showInference = true,
        showName = MonitorNameMode.AUTO,
      )

    assertEquals(1, database.recordFlag(playerId, "default", 1L, 0L))
    assertEquals(2, database.recordFlag(playerId, "default", 1L, 0L))
    assertEquals(2, database.getViolationLevel(playerId, "default"))

    database.saveMonitorSettings(playerId, settings)
    assertEquals(settings, database.loadMonitorSettings(playerId))

    database.resetViolationLevel(playerId, "default")
    assertEquals(0, database.getViolationLevel(playerId, "default"))

    database.recordFlag(playerId, "combat", 1L, 0L)
    database.recordFlag(playerId, "movement", 1L, 0L)
    database.resetAllViolationLevels(playerId)
    assertEquals(0, database.getViolationLevel(playerId, "combat"))
    assertEquals(0, database.getViolationLevel(playerId, "movement"))
    assertNull(database.loadMonitorSettings(UUID.randomUUID()))
  }

  private fun violation(uuid: UUID, name: String, verbose: String, at: Long) =
    Violation(
      serverName = "test-server",
      playerUUID = uuid,
      playerName = name,
      checkName = "x",
      verbose = verbose,
      vl = 1,
      createdAt = java.time.Instant.ofEpochMilli(at),
    )
}
