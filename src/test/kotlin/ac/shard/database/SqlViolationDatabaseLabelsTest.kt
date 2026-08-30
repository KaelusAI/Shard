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

import io.mockk.mockk
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test

class SqlViolationDatabaseLabelsTest {

  @Test
  fun `label buffers survive a round-trip`() {
    val database = freshDatabase("labels-roundtrip")
    val player = UUID.randomUUID()

    database.saveAiLabelBuffers(player, mapOf("aim" to 41.5, "trigger" to 12.0), UPDATED_AT)

    val loaded = database.loadAiLabelBuffers(player)
    assertEquals(setOf("aim", "trigger"), loaded.keys)
    assertEquals(41.5, loaded.getValue("aim").buffer)
    assertEquals(UPDATED_AT, loaded.getValue("aim").updatedAt)
  }

  @Test
  fun `saving replaces the set, so a label the model dropped disappears`() {
    val database = freshDatabase("labels-replace")
    val player = UUID.randomUUID()
    database.saveAiLabelBuffers(player, mapOf("aim" to 20.0, "trigger" to 30.0), UPDATED_AT)

    database.saveAiLabelBuffers(player, mapOf("aim" to 25.0), UPDATED_AT + 1)

    val loaded = database.loadAiLabelBuffers(player)
    assertEquals(setOf("aim"), loaded.keys, "trigger came off the model and must not linger")
    assertEquals(25.0, loaded.getValue("aim").buffer)
  }

  @Test
  fun `an empty set clears every label`() {
    val database = freshDatabase("labels-clear")
    val player = UUID.randomUUID()
    database.saveAiLabelBuffers(player, mapOf("aim" to 20.0), UPDATED_AT)

    database.saveAiLabelBuffers(player, emptyMap(), UPDATED_AT + 1)

    assertTrue(database.loadAiLabelBuffers(player).isEmpty())
  }

  @Test
  fun `one player's labels do not touch another's`() {
    val database = freshDatabase("labels-isolation")
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    database.saveAiLabelBuffers(first, mapOf("aim" to 10.0), UPDATED_AT)
    database.saveAiLabelBuffers(second, mapOf("trigger" to 20.0), UPDATED_AT)

    database.saveAiLabelBuffers(first, emptyMap(), UPDATED_AT + 1)

    assertTrue(database.loadAiLabelBuffers(first).isEmpty())
    assertEquals(setOf("trigger"), database.loadAiLabelBuffers(second).keys)
  }

  @Test
  fun `history returns the labels a flag was written with`() {
    val databaseFile = Files.createTempFile("shard-sqlite-labels-history-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    migrateFreshSqlite(jdbcUrl)
    val player = UUID.fromString("00000000-0000-0000-0000-000000000042")
    DriverManager.getConnection(jdbcUrl).use { connection ->
      connection
        .prepareStatement(
          """
          INSERT INTO violations(server, uuid, player_name, check_name, verbose, vl, created_at, labels)
          VALUES ('test', ?, 'Dyrz', 'AI', 'prob=0.99 buffer=51.0', 1, ?, 'aim,trigger')
          """
            .trimIndent()
        )
        .use { statement ->
          statement.setString(1, player.toString())
          statement.setLong(2, UPDATED_AT)
          statement.executeUpdate()
        }
    }

    val violations =
      SqlViolationDatabase(mockk(relaxed = true), Database.connect(jdbcUrl, "org.sqlite.JDBC"))
        .getViolations(player, 1, 10)

    assertEquals(1, violations.size)
    assertEquals("aim,trigger", violations.single().labels)
  }

  @Test
  fun `a player without label rows loads nothing rather than failing`() {
    val database = freshDatabase("labels-empty")

    assertTrue(database.loadAiLabelBuffers(UUID.randomUUID()).isEmpty())
    assertNull(database.loadAiBuffer(UUID.randomUUID()))
  }

  private fun freshDatabase(name: String): SqlViolationDatabase {
    val databaseFile = Files.createTempFile("shard-sqlite-$name-", ".db").toFile()
    databaseFile.deleteOnExit()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    migrateFreshSqlite(jdbcUrl)
    return SqlViolationDatabase(mockk(relaxed = true), Database.connect(jdbcUrl, "org.sqlite.JDBC"))
  }

  private fun migrateFreshSqlite(jdbcUrl: String) {
    Flyway.configure()
      .dataSource(jdbcUrl, null, null)
      .locations("classpath:db/migration/common", "classpath:db/migration/sqlite")
      .baselineVersion("0")
      .load()
      .migrate()
  }

  private companion object {
    const val UPDATED_AT = 1_766_344_566_889L
  }
}
