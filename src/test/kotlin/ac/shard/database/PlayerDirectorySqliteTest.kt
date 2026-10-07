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
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test

class PlayerDirectorySqliteTest {
  private fun freshUrl(): String {
    val file = Files.createTempFile("shard-players-", ".db").toFile()
    file.deleteOnExit()
    return "jdbc:sqlite:${file.absolutePath}"
  }

  private fun migrateFreshSqlite(url: String) {
    Flyway.configure()
      .dataSource(url, null, null)
      .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
      .baselineVersion("0")
      .load()
      .migrate()
  }

  private fun database(url: String) =
    SqlViolationDatabase(Database.connect(url, driver = "org.sqlite.JDBC"))

  @Test
  fun `a player is found by any spelling of the name and a new name replaces the old one`() {
    val url = freshUrl()
    migrateFreshSqlite(url)
    val database = database(url)
    val id = UUID.randomUUID()

    database.recordPlayer(id, "Kotik1903", 10L)

    assertEquals(id, database.findPlayersByName("kotik1903", 1).single().uuid)
    assertEquals(id, database.findPlayersByName("KOTIK1903", 1).single().uuid)
    assertNull(database.findPlayersByName("someone", 1).firstOrNull())

    database.recordPlayer(id, "Kotik2000", 20L)

    assertEquals("Kotik2000", database.findPlayer(id)?.name)
    assertEquals(20L, database.findPlayer(id)?.lastSeen)
    assertNull(database.findPlayersByName("kotik1903", 1).firstOrNull())
  }

  @Test
  fun `the freshest holder of a name wins when two accounts used it`() {
    val url = freshUrl()
    migrateFreshSqlite(url)
    val database = database(url)
    val old = UUID.randomUUID()
    val current = UUID.randomUUID()

    database.recordPlayer(old, "Shared", 10L)
    database.recordPlayer(current, "shared", 50L)

    assertEquals(current, database.findPlayersByName("SHARED", 1).single().uuid)
  }

  @Test
  fun `the repair migration rebuilds tables an earlier migration skipped and leaves data alone`() {
    val url = freshUrl()
    val flyway = { target: String ->
      Flyway.configure()
        .dataSource(url, null, null)
        .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
        .baselineVersion("0")
        .target(target)
        .load()
    }
    flyway("1002").migrate()
    DriverManager.getConnection(url).use { connection ->
      connection.createStatement().use {
        it.execute("DROP TABLE ai_label_buffers")
        it.execute("ALTER TABLE monitor_settings DROP COLUMN label_focus")
      }
    }

    flyway("latest").migrate()

    DriverManager.getConnection(url).use { connection ->
      connection.metaData.getTables(null, null, "ai_label_buffers", arrayOf("TABLE")).use {
        assertTrue(it.next(), "ai_label_buffers is back")
      }
      connection.metaData.getColumns(null, null, "monitor_settings", "label_focus").use {
        assertTrue(it.next(), "label_focus is back")
      }
    }
  }

  @Test
  fun `players already in the violations history are known after the migration`() {
    val url = freshUrl()
    Flyway.configure()
      .dataSource(url, null, null)
      .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
      .baselineVersion("0")
      .target("1006")
      .load()
      .migrate()
    val id = UUID.randomUUID()
    DriverManager.getConnection(url).use { connection ->
      connection
        .prepareStatement(
          "INSERT INTO violations(server, uuid, player_name, check_name, verbose, vl, created_at, " +
            "created_at_instant) VALUES ('s', ?, ?, 'AI', 'v', 1, ?, '2026-01-01 00:00:00.000')"
        )
        .use { insert ->
          insert.setString(1, id.toString())
          insert.setString(2, "OldName")
          insert.setLong(3, 100L)
          insert.executeUpdate()
          insert.setString(1, id.toString())
          insert.setString(2, "NewName")
          insert.setLong(3, 900L)
          insert.executeUpdate()
        }
    }

    migrateFreshSqlite(url)

    val found = assertNotNull(database(url).findPlayersByName("newname", 1).singleOrNull())
    assertEquals(id, found.uuid)
    assertEquals("NewName", found.name)
    assertEquals(900L, found.lastSeen)
    assertNull(database(url).findPlayersByName("oldname", 1).firstOrNull())
  }
}
