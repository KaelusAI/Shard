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

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test

class FlywaySqliteMigrationsTest {

  @Test
  fun `applies sqlite sql and java migrations`() {
    val databaseFile = Files.createTempFile("shard-flyway-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    Flyway.configure()
      .dataSource(jdbcUrl, null, null)
      .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
      .baselineVersion("0")
      .load()
      .migrate()

    DriverManager.getConnection(jdbcUrl).use { connection ->
      assertTrue(tableExists(connection, "violations"))
      assertTrue(tableExists(connection, "shard_punishments"))
      assertTrue(!tableExists(connection, "sloth_punishments"))
      assertTrue(tableExists(connection, "monitor_settings"))
      assertTrue(columnExists(connection, "violations", "created_at_instant"))
      assertTrue(columnExists(connection, "monitor_settings", "show_name"))
      val versions = appliedVersions(connection)
      assertTrue(!versions.contains("0.1"))
      assertTrue(versions.contains("1"))
      assertTrue(versions.contains("2"))
      assertTrue(versions.contains("3"))
    }
  }

  @Test
  fun `migrates legacy sqlite schema without monitor settings table`() {
    val databaseFile = Files.createTempFile("shard-legacy-flyway-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    DriverManager.getConnection(jdbcUrl).use { connection ->
      createLegacySqliteSchemaWithoutMonitorSettings(connection)
    }

    DriverManager.getConnection(jdbcUrl).use { connection ->
      insertLegacyViolation(connection, createdAt = 1_765_033_573_944L)
    }

    migrateSqliteDatabase(jdbcUrl)

    DriverManager.getConnection(jdbcUrl).use { connection ->
      assertMigratedSchema(connection)
      assertMonitorSettingsTable(connection)
      assertCreatedAtInstantBackfilled(connection, expected = "2025-12-06 15:06:13.944")
    }
  }

  @Test
  fun `migrates legacy sqlite schema with monitor settings missing show name`() {
    val databaseFile = Files.createTempFile("shard-monitor-legacy-flyway-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    DriverManager.getConnection(jdbcUrl).use { connection ->
      createLegacySqliteSchemaWithMonitorSettings(connection)
      insertLegacyViolation(connection, createdAt = 1_766_344_566_889L)
    }

    migrateSqliteDatabase(jdbcUrl)

    DriverManager.getConnection(jdbcUrl).use { connection ->
      assertMigratedSchema(connection)
      assertTrue(columnExists(connection, "monitor_settings", "show_name"))
      assertCreatedAtInstantBackfilled(connection, expected = "2025-12-21 19:16:06.889")
    }
  }

  @Test
  fun `keeps sqlite compat validation working after legacy migration enters history`() {
    val databaseFile = Files.createTempFile("shard-legacy-rerun-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    DriverManager.getConnection(jdbcUrl).use { connection ->
      createLegacySqliteSchemaWithoutMonitorSettings(connection)
      insertLegacyViolation(connection, createdAt = 1_765_033_573_944L)
    }

    migrateSqliteDatabase(jdbcUrl)
    migrateSqliteDatabase(jdbcUrl)

    DriverManager.getConnection(jdbcUrl).use { connection ->
      assertMigratedSchema(connection)
      assertMonitorSettingsTable(connection)
    }
  }

  @Test
  fun `migrates partial sqlite schema when only monitor settings exists`() {
    val databaseFile = Files.createTempFile("shard-monitor-only-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    DriverManager.getConnection(jdbcUrl).use { connection ->
      connection.createStatement().use { statement ->
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
      }
    }

    migrateSqliteDatabase(jdbcUrl)

    DriverManager.getConnection(jdbcUrl).use { connection ->
      assertTrue(tableExists(connection, "violations"))
      assertTrue(tableExists(connection, "shard_punishments"))
      assertMonitorSettingsTable(connection)
      assertTrue(hasAppliedMigrationVersion(connection, "1"))
      assertTrue(hasAppliedMigrationVersion(connection, "2"))
      assertTrue(hasAppliedMigrationVersion(connection, "3"))
      assertTrue(!hasAppliedMigrationVersion(connection, "0.1"))
    }
  }

  @Test
  fun `migrates partial sqlite schema when only punishments table exists`() {
    val databaseFile = Files.createTempFile("shard-punishments-only-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

    DriverManager.getConnection(jdbcUrl).use { connection ->
      connection.createStatement().use { statement ->
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
      }
    }

    migrateSqliteDatabase(jdbcUrl)

    DriverManager.getConnection(jdbcUrl).use { connection ->
      assertTrue(tableExists(connection, "violations"))
      assertTrue(tableExists(connection, "monitor_settings"))
      assertTrue(tableExists(connection, "shard_punishments"))
      assertTrue(!tableExists(connection, "sloth_punishments"))
      assertTrue(columnExists(connection, "violations", "created_at_instant"))
      assertTrue(hasAppliedMigrationVersion(connection, "1"))
      assertTrue(hasAppliedMigrationVersion(connection, "2"))
      assertTrue(hasAppliedMigrationVersion(connection, "3"))
      assertTrue(!hasAppliedMigrationVersion(connection, "0.1"))
    }
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

  private fun appliedVersions(connection: Connection): Set<String> {
    val versions = mutableSetOf<String>()
    connection.createStatement().use { statement ->
      statement
        .executeQuery(
          "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL"
        )
        .use { resultSet ->
          while (resultSet.next()) {
            resultSet.getString("version")?.takeIf { it.isNotBlank() }?.let(versions::add)
          }
        }
    }
    return versions
  }

  private fun hasAppliedMigrationVersion(connection: Connection, version: String): Boolean {
    if (!tableExists(connection, "flyway_schema_history")) {
      return false
    }
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

  private fun indexExists(connection: Connection, indexName: String): Boolean {
    connection.metaData.getIndexInfo(null, null, "violations", false, false).use { resultSet ->
      while (resultSet.next()) {
        if (indexName.equals(resultSet.getString("INDEX_NAME"), ignoreCase = true)) {
          return true
        }
      }
    }
    return false
  }

  private fun migrateSqliteDatabase(jdbcUrl: String) {
    val locations = mutableListOf("classpath:db/migration/common", "classpath:db/migration/sqlite")
    var requiresBaseline = false
    DriverManager.getConnection(jdbcUrl).use { connection ->
      if (sqliteLegacyCompatRequired(connection) || hasAppliedMigrationVersion(connection, "0.1")) {
        locations += "classpath:db/migration/sqlitecompat"
      }
      requiresBaseline = sqliteRequiresExplicitBaseline(connection)
    }

    val flyway =
      Flyway.configure()
        .dataSource(jdbcUrl, null, null)
        .locations(*locations.toTypedArray())
        .baselineVersion("0")
        .load()

    if (requiresBaseline) {
      flyway.baseline()
    }

    flyway.migrate()
  }

  private fun assertMigratedSchema(connection: Connection) {
    assertTrue(columnExists(connection, "violations", "created_at_instant"))
    assertTrue(indexExists(connection, "violations_uuid_created_at_instant_idx"))
    assertTrue(indexExists(connection, "violations_created_at_instant_idx"))
    val versions = appliedVersions(connection)
    assertTrue(versions.contains("0.1"))
    assertTrue(versions.contains("1"))
    assertTrue(versions.contains("2"))
    assertTrue(versions.contains("3"))
  }

  private fun assertMonitorSettingsTable(connection: Connection) {
    assertTrue(tableExists(connection, "monitor_settings"))
    assertTrue(columnExists(connection, "monitor_settings", "show_name"))
    assertTrue(columnExists(connection, "monitor_settings", "show_collect"))
    assertTrue(columnExists(connection, "monitor_settings", "show_inference"))
  }

  private fun assertCreatedAtInstantBackfilled(connection: Connection, expected: String) {
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT created_at_instant FROM violations LIMIT 1").use { resultSet ->
        assertTrue(resultSet.next())
        assertTrue(resultSet.getString("created_at_instant") == expected)
      }
    }
  }

  private fun createLegacySqliteSchemaWithoutMonitorSettings(connection: Connection) {
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
        "CREATE INDEX idx_violations_uuid_time ON violations(uuid, created_at DESC)"
      )
      statement.executeUpdate("CREATE INDEX idx_violations_time ON violations(created_at DESC)")
    }
  }

  private fun createLegacySqliteSchemaWithMonitorSettings(connection: Connection) {
    createLegacySqliteSchemaWithoutMonitorSettings(connection)
    connection.createStatement().use { statement ->
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
    connection
      .prepareStatement("INSERT INTO sloth_punishments(uuid, punish_group, vl) VALUES (?, ?, ?)")
      .use { statement ->
        statement.setString(1, "00000000-0000-0000-0000-000000000001")
        statement.setString(2, "default")
        statement.setInt(3, 12)
        statement.executeUpdate()
      }
    if (tableExists(connection, "monitor_settings")) {
      connection
        .prepareStatement(
          """
          INSERT INTO monitor_settings(uuid, mode, theme, show_ping, show_dmg, show_trend)
          VALUES (?, ?, ?, ?, ?, ?)
          """
            .trimIndent()
        )
        .use { statement ->
          statement.setString(1, "00000000-0000-0000-0000-000000000001")
          statement.setString(2, "compact")
          statement.setString(3, "calm")
          statement.setInt(4, 1)
          statement.setInt(5, 1)
          statement.setInt(6, 1)
          statement.executeUpdate()
        }
    }
  }
}
