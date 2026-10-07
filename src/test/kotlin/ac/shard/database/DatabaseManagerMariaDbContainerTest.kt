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
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Tag("container")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseManagerMariaDbContainerTest {

  @Test
  fun `database manager starts against mariadb and repository operations work`() {
    withMariaDbContainer("shard_startup") { container ->
      val runtime = createRuntime(container)
      val manager = DatabaseManager(runtime.plugin, runtime.configManager).apply { start() }
      val playerId = UUID.randomUUID()
      val settings =
        MonitorSettings(
          mode = MonitorMode.COMPACT,
          theme = MonitorTheme.CALM,
          showPing = true,
          showDmg = false,
          showTrend = true,
          showCollect = true,
          showInference = true,
          showName = MonitorNameMode.AUTO,
          outputs = setOf(MonitorOutputKind.SIDEBAR),
          chatStyle = MonitorChatStyle.LIVE,
        )

      try {
        assertTrue(manager.isAvailable)
        assertNull(manager.failureCause)

        assertEquals(1, manager.database.recordFlag(playerId, "default", 1L, 0L))
        assertEquals(2, manager.database.recordFlag(playerId, "default", 1L, 0L))
        assertEquals(2, manager.database.getViolationLevel(playerId, "default"))

        manager.database.saveMonitorSettings(playerId, settings)
        assertEquals(settings, manager.database.loadMonitorSettings(playerId))

        container.createConnection("").use { connection -> assertCurrentMariaDbSchema(connection) }
      } finally {
        manager.stop()
      }
    }
  }

  @Test
  fun `mariadb data written through database manager survives restart`() {
    withMariaDbContainer("shard_restart") { container ->
      val runtime = createRuntime(container)
      val playerId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
      val settings =
        MonitorSettings(
          mode = MonitorMode.COMPACT,
          theme = MonitorTheme.CALM,
          showPing = true,
          showDmg = false,
          showTrend = true,
          showCollect = true,
          showInference = true,
          showName = MonitorNameMode.AUTO,
        )

      DatabaseManager(runtime.plugin, runtime.configManager)
        .apply { start() }
        .use { manager ->
          assertTrue(manager.isAvailable)
          assertEquals(1, manager.database.recordFlag(playerId, "default", 1L, 0L))
          assertEquals(2, manager.database.recordFlag(playerId, "default", 1L, 0L))
          manager.database.saveMonitorSettings(playerId, settings)
        }

      DatabaseManager(runtime.plugin, runtime.configManager)
        .apply { start() }
        .use { manager ->
          assertTrue(manager.isAvailable)
          assertNull(manager.failureCause)
          assertEquals(2, manager.database.getViolationLevel(playerId, "default"))
          assertEquals(settings, manager.database.loadMonitorSettings(playerId))
        }
    }
  }

  @Test
  fun `database manager degrades to in memory storage after mariadb runtime outage`() {
    withMariaDbContainer("shard_outage") { container ->
      val runtime = createRuntime(container)
      val manager = DatabaseManager(runtime.plugin, runtime.configManager).apply { start() }
      val playerId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
      val settings =
        MonitorSettings(
          mode = MonitorMode.COMPACT,
          theme = MonitorTheme.CALM,
          showPing = true,
          showDmg = false,
          showTrend = true,
          showCollect = true,
          showInference = true,
          showName = MonitorNameMode.AUTO,
        )

      try {
        assertTrue(manager.isAvailable)
        container.stop()

        assertEquals(1, manager.database.recordFlag(playerId, "default", 1L, 0L))
        manager.database.saveMonitorSettings(playerId, settings)

        assertFalse(manager.isAvailable)
        assertNotNull(manager.failureCause)
        assertEquals(1, manager.database.getViolationLevel(playerId, "default"))
        assertEquals(settings, manager.database.loadMonitorSettings(playerId))
        verify(exactly = 1) {
          runtime.logger.log(
            Level.WARNING,
            "Lost the connection to the database. Shard keeps working in memory and will write " +
              "the pending changes once the database is back.",
            any<Throwable>(),
          )
        }
      } finally {
        manager.stop()
      }
    }
  }

  @Test
  fun `a database left by 1_3_5 upgrades in place and keeps its history and punishments`() {
    withMariaDbContainer("shard_old") { container ->
      org.flywaydb.core.Flyway.configure()
        .dataSource(container.jdbcUrl, container.username, container.password)
        .locations("classpath:db/migration/common", "classpath:db/migration/mysql")
        .target("12")
        .load()
        .migrate()
      val player = UUID.randomUUID()
      container.createConnection("").use { connection ->
        connection.createStatement().use {
          it.execute(
            "INSERT INTO violations(server, uuid, player_name, check_name, verbose, vl, " +
              "created_at) VALUES ('old', '$player', 'OldTimer', 'AI', 'v', 4, 1700000000000)"
          )
          it.execute(
            "INSERT INTO shard_punishments(uuid, punish_group, vl) VALUES ('$player', 'general', 3)"
          )
        }
      }

      val runtime = createRuntime(container, "shard_old")
      DatabaseManager(runtime.plugin, runtime.configManager)
        .apply { start() }
        .use { manager ->
          assertTrue(manager.isAvailable)
          assertNull(manager.failureCause)
          assertEquals(1, manager.database.getLogCount(player))
          assertEquals(3, manager.database.getViolationLevel(player, "general"))
          assertEquals(player, manager.database.findPlayersByName("oldtimer", 1).single().uuid)
          assertEquals(
            4,
            manager.database.recordFlag(player, "general", System.currentTimeMillis(), 0L),
          )
        }
    }
  }

  @Test
  fun `two servers sharing one mariadb each get a complete schema in their own database`() {
    withMariaDbContainer("shard_first") { container ->
      java.sql.DriverManager.getConnection(
          "jdbc:mariadb://${container.host}:${container.firstMappedPort}/",
          "root",
          container.password,
        )
        .use { admin ->
          admin.createStatement().use {
            it.execute("CREATE DATABASE shard_second")
            it.execute("GRANT ALL ON shard_second.* TO 'shard'@'%'")
          }
        }

      val first = createRuntime(container, "shard_first")
      DatabaseManager(first.plugin, first.configManager)
        .apply { start() }
        .use { assertTrue(it.isAvailable) }

      val second = createRuntime(container, "shard_second")
      DatabaseManager(second.plugin, second.configManager)
        .apply { start() }
        .use { manager ->
          assertTrue(manager.isAvailable, "the second database must not be treated as migrated")
          assertNull(manager.failureCause)
          val player = UUID.randomUUID()
          manager.database.recordPlayer(player, "Second", 1L)
          assertEquals("Second", manager.database.findPlayer(player)?.name)
          assertEquals(1, manager.database.recordFlag(player, "general", 1L, 0L))
        }

      container.createConnection("").use { connection ->
        connection.createStatement().use { statement ->
          statement.execute("USE shard_second")
          statement.executeQuery("SHOW TABLES LIKE 'shard_players'").use { assertTrue(it.next()) }
          statement.executeQuery("SHOW TABLES LIKE 'ai_label_buffers'").use {
            assertTrue(it.next())
          }
          statement.executeQuery("SHOW TABLES LIKE 'shard_punish_flags'").use {
            assertTrue(it.next())
          }
        }
      }
    }
  }

  private fun createRuntime(
    container: MariaDBContainer<*>,
    database: String = container.databaseName,
  ): TestRuntime {
    val dataDirectory = Files.createTempDirectory("shard-mariadb-runtime-")
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.dataFolder } returns dataDirectory.toFile()
    every { plugin.logger } returns logger

    writeMariaDbConfig(dataDirectory, container, database)
    copyResourceTo(dataDirectory, "punishments.yml")
    copyResourceTo(dataDirectory, "monitor.yml")

    return TestRuntime(plugin, ConfigManager(plugin, CredentialsStore(plugin)), logger)
  }

  private fun withMariaDbContainer(databaseName: String, block: (MariaDBContainer<*>) -> Unit) {
    MariaDBContainer("mariadb:10.3.39")
      .withDatabaseName(databaseName)
      .withUsername("shard")
      .withPassword("shard")
      .use { container ->
        container.start()
        block(container)
      }
  }

  private fun writeMariaDbConfig(
    dataDirectory: Path,
    container: MariaDBContainer<*>,
    database: String,
  ) {
    Files.writeString(
      dataDirectory.resolve("config.yml"),
      """
      locale: "en"
      database:
        type: mariadb
        mysql:
          host: "${container.host}"
          port: ${container.firstMappedPort}
          database: "$database"
          username: "${container.username}"
          password: "${container.password}"
          use-ssl: false
      """
        .trimIndent(),
    )
  }

  private fun copyResourceTo(directory: Path, resourceName: String) {
    javaClass.classLoader.getResourceAsStream(resourceName).use { stream ->
      checkNotNull(stream) { "Missing test resource $resourceName" }
      Files.newOutputStream(directory.resolve(resourceName)).use { output -> stream.copyTo(output) }
    }
  }

  private fun assertCurrentMariaDbSchema(connection: java.sql.Connection) {
    assertTrue(columnExists(connection, "violations", "created_at_instant"))
    assertTrue(columnExists(connection, "monitor_settings", "show_name"))
    assertTrue(hasAppliedVersion(connection, "1"))
  }

  private fun columnExists(
    connection: java.sql.Connection,
    tableName: String,
    columnName: String,
  ): Boolean {
    connection.metaData.getColumns(null, null, tableName, columnName).use { resultSet ->
      return resultSet.next()
    }
  }

  private fun hasAppliedVersion(connection: java.sql.Connection, version: String): Boolean {
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

  private data class TestRuntime(
    val plugin: Shard,
    val configManager: ConfigManager,
    val logger: Logger,
  )

  private fun DatabaseManager.use(block: (DatabaseManager) -> Unit) {
    try {
      block(this)
    } finally {
      stop()
    }
  }
}
