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
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import io.mockk.mockk
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test

class SqlViolationDatabaseSqliteTest {

  @Test
  fun `reads sqlite violations through sqlite-compatible instant decoding`() {
    val databaseFile = Files.createTempFile("shard-sqlite-violations-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    migrateFreshSqlite(jdbcUrl)

    val createdAt = 1_766_344_566_889L
    val createdAtInstantText =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDateTime())
    val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    DriverManager.getConnection(jdbcUrl).use { connection ->
      connection
        .prepareStatement(
          """
          INSERT INTO violations(server, uuid, player_name, check_name, verbose, vl, created_at, created_at_instant)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """
            .trimIndent()
        )
        .use { statement ->
          statement.setString(1, "test")
          statement.setString(2, playerId.toString())
          statement.setString(3, "PlayerOne")
          statement.setString(4, "Aim")
          statement.setString(5, "legacy")
          statement.setInt(6, 12)
          statement.setLong(7, createdAt)
          statement.setString(8, createdAtInstantText)
          statement.executeUpdate()
        }
    }

    val configManager = mockk<ConfigManager>(relaxed = true)
    val database = Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
    val violationDatabase = SqlViolationDatabase(configManager, database)

    val playerViolations = violationDatabase.getViolations(playerId, page = 1, limit = 10)
    val recentViolations =
      violationDatabase.getViolations(page = 1, limit = 10, since = createdAt - 1)

    assertEquals(1, playerViolations.size)
    assertEquals(1, recentViolations.size)
    assertEquals(Instant.ofEpochMilli(createdAt), playerViolations.single().createdAt)
    assertEquals(1, violationDatabase.getLogCount(since = createdAt - 1))
    assertEquals(1, violationDatabase.getUniqueViolatorsSince(createdAt - 1))
  }

  @Test
  fun `stores punishments and monitor settings on fresh sqlite`() {
    val databaseFile = Files.createTempFile("shard-sqlite-runtime-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    migrateFreshSqlite(jdbcUrl)

    val configManager = mockk<ConfigManager>(relaxed = true)
    val database = Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
    val violationDatabase = SqlViolationDatabase(configManager, database)
    val playerId = UUID.randomUUID()
    val settings =
      MonitorSettings(
        mode = MonitorMode.COMPACT,
        theme = MonitorTheme.CALM,
        showPing = true,
        showDmg = false,
        showTrend = true,
        showName = MonitorNameMode.AUTO,
      )

    assertEquals(1, violationDatabase.incrementViolationLevel(playerId, "default"))
    assertEquals(2, violationDatabase.incrementViolationLevel(playerId, "default"))
    assertEquals(2, violationDatabase.getViolationLevel(playerId, "default"))

    violationDatabase.saveMonitorSettings(playerId, settings)
    assertEquals(settings, violationDatabase.loadMonitorSettings(playerId))

    violationDatabase.resetViolationLevel(playerId, "default")
    assertEquals(0, violationDatabase.getViolationLevel(playerId, "default"))
    assertNotNull(violationDatabase.loadMonitorSettings(playerId))
  }

  @Test
  fun `monitor output and chat style round-trip`() {
    val databaseFile = Files.createTempFile("shard-sqlite-output-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    migrateFreshSqlite(jdbcUrl)
    val database = Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
    val violationDatabase = SqlViolationDatabase(mockk(relaxed = true), database)
    val playerId = UUID.randomUUID()
    val settings =
      MonitorSettings(
        mode = MonitorMode.FULL,
        theme = MonitorTheme.VIVID,
        showPing = false,
        showDmg = true,
        showTrend = false,
        showName = MonitorNameMode.ALWAYS,
        outputs = setOf(MonitorOutputKind.BOSSBAR),
        chatStyle = MonitorChatStyle.LIVE,
      )

    violationDatabase.saveMonitorSettings(playerId, settings)

    val loaded = violationDatabase.loadMonitorSettings(playerId)
    assertEquals(setOf(MonitorOutputKind.BOSSBAR), loaded?.outputs)
    assertEquals(MonitorChatStyle.LIVE, loaded?.chatStyle)
    assertEquals(settings, loaded)
  }

  @Test
  fun `rows written before the output columns existed load as action bar`() {
    val databaseFile = Files.createTempFile("shard-sqlite-legacy-row-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    migrateFreshSqlite(jdbcUrl)
    val playerId = UUID.randomUUID()
    DriverManager.getConnection(jdbcUrl).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO monitor_settings (uuid, mode, theme, show_ping, show_dmg, show_trend, " +
            "show_name) VALUES ('$playerId', 'COMPACT', 'CALM', 1, 1, 1, 'AUTO')"
        )
      }
    }
    val database = Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")

    val loaded = SqlViolationDatabase(mockk(relaxed = true), database).loadMonitorSettings(playerId)

    assertEquals(setOf(MonitorOutputKind.ACTIONBAR), loaded?.outputs)
    assertEquals(MonitorChatStyle.LIVE, loaded?.chatStyle)
  }

  private fun migrateFreshSqlite(jdbcUrl: String) {
    Flyway.configure()
      .dataSource(jdbcUrl, null, null)
      .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
      .baselineVersion("0")
      .load()
      .migrate()
  }
}
