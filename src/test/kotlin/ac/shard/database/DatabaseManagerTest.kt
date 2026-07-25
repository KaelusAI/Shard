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

import ac.shard.Shard
import ac.shard.config.ConfigManager
import ac.shard.connect.CredentialsStore
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test

private const val TRANSITIONAL_V1_CHECKSUM = 1087069697

class DatabaseManagerTest {

  @Test
  fun `fresh sqlite startup creates the current schema with persistent storage enabled`() {
    val runtime = createRuntime("fresh")

    val manager = createManager(runtime)
    try {
      assertTrue(manager.isAvailable)
      assertNull(manager.failureCause)
      assertTrue(manager.database is ResilientViolationDatabase)
    } finally {
      manager.shutdown()
    }

    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      assertCurrentSchema(connection)
      assertFalse(hasAppliedVersion(connection, "0.1"))
    }
    assertFalse(Files.exists(runtime.backupDirectory))
  }

  @Test
  fun `legacy sqlite startup migrates to the current schema and keeps persistent storage enabled`() {
    val runtime = createRuntime("legacy")
    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      createLegacySqliteSchema(connection)
      insertLegacyViolation(connection, createdAt = 1_765_033_573_944L)
    }

    val manager = createManager(runtime)
    try {
      assertTrue(manager.isAvailable)
      assertNull(manager.failureCause)
    } finally {
      manager.shutdown()
    }

    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      assertCurrentSchema(connection)
      assertTrue(hasAppliedVersion(connection, "0.1"))
    }
  }

  @Test
  fun `current sqlite schema startup is a no-op and does not create backups`() {
    val runtime = createRuntime("noop")
    migrateFreshSqlite(runtime.jdbcUrl)
    val appliedBefore =
      DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
        migrationHistoryCount(connection)
      }

    val manager = createManager(runtime)
    try {
      assertTrue(manager.isAvailable)
      assertNull(manager.failureCause)
    } finally {
      manager.shutdown()
    }

    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      assertCurrentSchema(connection)
      assertEquals(appliedBefore, migrationHistoryCount(connection))
    }
    assertFalse(Files.exists(runtime.backupDirectory))
  }

  @Test
  fun `sqlite data written through database manager survives restart`() {
    val runtime = createRuntime("sqlite-restart")
    val playerId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    val settings =
      MonitorSettings(
        mode = MonitorMode.COMPACT,
        theme = MonitorTheme.CALM,
        showPing = true,
        showDmg = false,
        showTrend = true,
        showName = MonitorNameMode.AUTO,
      )

    createManager(runtime).use { manager ->
      assertTrue(manager.isAvailable)
      assertEquals(1, manager.database.incrementViolationLevel(playerId, "default"))
      assertEquals(2, manager.database.incrementViolationLevel(playerId, "default"))
      manager.database.saveMonitorSettings(playerId, settings)
    }

    createManager(runtime).use { manager ->
      assertTrue(manager.isAvailable)
      assertEquals(2, manager.database.getViolationLevel(playerId, "default"))
      assertEquals(settings, manager.database.loadMonitorSettings(playerId))
    }
  }

  @Test
  fun `repairable checksum mismatch recovers during startup and preserves persistent storage`() {
    val runtime = createRuntime("repairable")
    migrateFreshSqlite(runtime.jdbcUrl)
    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "UPDATE flyway_schema_history SET checksum = $TRANSITIONAL_V1_CHECKSUM WHERE version = '1'"
        )
      }
    }

    val manager = createManager(runtime)
    try {
      assertTrue(manager.isAvailable)
      assertNull(manager.failureCause)
    } finally {
      manager.shutdown()
    }

    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      assertCurrentSchema(connection)
      assertTrue(readChecksum(connection, "1") != TRANSITIONAL_V1_CHECKSUM)
    }
    assertTrue(countBackups(runtime.backupDirectory) == 1)
  }

  @Test
  fun `unrepairable checksum mismatch degrades to in-memory storage and creates a backup`() {
    val runtime = createRuntime("degraded")
    migrateFreshSqlite(runtime.jdbcUrl)
    DriverManager.getConnection(runtime.jdbcUrl).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "UPDATE flyway_schema_history SET checksum = 123456 WHERE version = '1'"
        )
      }
    }

    val manager = createManager(runtime)
    try {
      assertFalse(manager.isAvailable)
      assertNotNull(manager.failureCause)

      val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
      val settings =
        MonitorSettings(
          mode = MonitorMode.COMPACT,
          theme = MonitorTheme.CALM,
          showPing = true,
          showDmg = true,
          showTrend = true,
          showName = MonitorNameMode.AUTO,
        )

      assertEquals(1, manager.database.incrementViolationLevel(playerId, "default"))
      manager.database.saveMonitorSettings(playerId, settings)
      assertEquals(settings, manager.database.loadMonitorSettings(playerId))
      assertTrue(countBackups(runtime.backupDirectory) == 1)
    } finally {
      manager.shutdown()
    }

    verify {
      runtime.logger.log(
        Level.WARNING,
        match<String> { message ->
          message.contains("Persistent database storage is unavailable") &&
            message.contains("in-memory storage")
        },
        any<Throwable>(),
      )
    }
  }

  private fun createManager(runtime: TestRuntime): DatabaseManager {
    return DatabaseManager(runtime.plugin, runtime.configManager)
  }

  private fun createRuntime(name: String): TestRuntime {
    val dataDirectory = Files.createTempDirectory("shard-db-manager-$name-")
    copyResourceTo(dataDirectory, "config.yml")
    copyResourceTo(dataDirectory, "punishments.yml")
    copyResourceTo(dataDirectory, "monitor.yml")

    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.dataFolder } returns dataDirectory.toFile()
    every { plugin.logger } returns logger

    return TestRuntime(
      dataDirectory = dataDirectory,
      databaseFile = dataDirectory.resolve("violations.db"),
      backupDirectory = dataDirectory.resolve("db-backups"),
      jdbcUrl = "jdbc:sqlite:${dataDirectory.resolve("violations.db").absolutePathString()}",
      plugin = plugin,
      logger = logger,
      configManager = ConfigManager(plugin, CredentialsStore(plugin)),
    )
  }

  private fun copyResourceTo(directory: Path, resourceName: String) {
    javaClass.classLoader.getResourceAsStream(resourceName).use { stream ->
      checkNotNull(stream) { "Missing test resource $resourceName" }
      Files.newOutputStream(directory.resolve(resourceName)).use { output -> stream.copyTo(output) }
    }
  }

  private fun migrateFreshSqlite(jdbcUrl: String) {
    Flyway.configure()
      .dataSource(jdbcUrl, null, null)
      .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
      .baselineVersion("0")
      .load()
      .migrate()
  }

  private fun createLegacySqliteSchema(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        CREATE TABLE violations (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          server TEXT NOT NULL,
          uuid TEXT NOT NULL,
          player_name TEXT NOT NULL,
          check_name TEXT NOT NULL,
          verbose TEXT NOT NULL,
          vl INTEGER NOT NULL,
          created_at INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      statement.executeUpdate(
        """
        CREATE TABLE sloth_punishments (
          uuid TEXT NOT NULL,
          punish_group TEXT NOT NULL,
          vl INTEGER NOT NULL,
          PRIMARY KEY (uuid, punish_group)
        )
        """
          .trimIndent()
      )
      statement.executeUpdate(
        """
        CREATE TABLE monitor_settings (
          uuid TEXT NOT NULL PRIMARY KEY,
          mode TEXT NOT NULL,
          theme TEXT NOT NULL,
          show_ping INTEGER NOT NULL,
          show_dmg INTEGER NOT NULL,
          show_trend INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      statement.executeUpdate(
        "CREATE INDEX idx_violations_uuid_time ON violations(uuid, created_at DESC)"
      )
      statement.executeUpdate("CREATE INDEX idx_violations_time ON violations(created_at DESC)")
    }
  }

  private fun insertLegacyViolation(connection: Connection, createdAt: Long) {
    connection
      .prepareStatement(
        """
        INSERT INTO violations(server, uuid, player_name, check_name, verbose, vl, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """
          .trimIndent()
      )
      .use { statement ->
        statement.setString(1, "test")
        statement.setString(2, "00000000-0000-0000-0000-000000000001")
        statement.setString(3, "PlayerOne")
        statement.setString(4, "Aim")
        statement.setString(5, "legacy")
        statement.setInt(6, 12)
        statement.setLong(7, createdAt)
        statement.executeUpdate()
      }
  }

  private fun assertCurrentSchema(connection: Connection) {
    assertTrue(tableExists(connection, "violations"))
    assertTrue(tableExists(connection, "shard_punishments"))
    assertTrue(tableExists(connection, "monitor_settings"))
    assertTrue(columnExists(connection, "violations", "created_at_instant"))
    assertTrue(columnExists(connection, "monitor_settings", "show_name"))
    assertTrue(hasAppliedVersion(connection, "1"))
    assertTrue(hasAppliedVersion(connection, "2"))
    assertTrue(hasAppliedVersion(connection, "3"))
  }

  private fun tableExists(connection: Connection, tableName: String): Boolean {
    connection.metaData.getTables(null, null, tableName, arrayOf("TABLE")).use { resultSet ->
      return resultSet.next()
    }
  }

  private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean {
    connection.metaData.getColumns(null, null, tableName, columnName).use { resultSet ->
      return resultSet.next()
    }
  }

  private fun hasAppliedVersion(connection: Connection, version: String): Boolean {
    connection
      .prepareStatement(
        """
        SELECT 1
        FROM flyway_schema_history
        WHERE version = ? AND success = 1
        LIMIT 1
        """
          .trimIndent()
      )
      .use { statement ->
        statement.setString(1, version)
        statement.executeQuery().use { resultSet ->
          return resultSet.next()
        }
      }
  }

  private fun migrationHistoryCount(connection: Connection): Int {
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1").use {
        resultSet ->
        resultSet.next()
        return resultSet.getInt(1)
      }
    }
  }

  private fun readChecksum(connection: Connection, version: String): Int? {
    connection
      .prepareStatement(
        """
        SELECT checksum
        FROM flyway_schema_history
        WHERE version = ? AND success = 1
        ORDER BY installed_rank DESC
        LIMIT 1
        """
          .trimIndent()
      )
      .use { statement ->
        statement.setString(1, version)
        statement.executeQuery().use { resultSet ->
          return if (resultSet.next()) resultSet.getInt("checksum") else null
        }
      }
  }

  private fun countBackups(backupDirectory: Path): Int {
    if (!Files.exists(backupDirectory)) {
      return 0
    }
    Files.list(backupDirectory).use { paths ->
      return paths.filter { path -> Files.isRegularFile(path) }.count().toInt()
    }
  }

  private data class TestRuntime(
    val dataDirectory: Path,
    val databaseFile: Path,
    val backupDirectory: Path,
    val jdbcUrl: String,
    val plugin: Shard,
    val logger: Logger,
    val configManager: ConfigManager,
  )

  private fun DatabaseManager.use(block: (DatabaseManager) -> Unit) {
    try {
      block(this)
    } finally {
      shutdown()
    }
  }
}
