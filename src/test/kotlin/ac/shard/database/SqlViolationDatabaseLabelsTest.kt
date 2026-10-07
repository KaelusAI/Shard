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
  fun `clearing labels removes only the matching ones`() {
    val database = freshDatabase("labels-clear-some")
    val player = UUID.randomUUID()
    database.saveAiBuffer(player, 30.0, UPDATED_AT)
    database.saveAiLabelBuffers(player, mapOf("x" to 20.0, "b/y" to 30.0), UPDATED_AT)

    database.clearAiLabelBuffers(player) { it == "x" }

    assertEquals(setOf("b/y"), database.loadAiLabelBuffers(player).keys)
    assertEquals(30.0, database.loadAiBuffer(player)?.buffer)
  }

  @Test
  fun `clearing the last label also clears the player buffer`() {
    val database = freshDatabase("labels-clear-last")
    val player = UUID.randomUUID()
    database.saveAiBuffer(player, 30.0, UPDATED_AT)
    database.saveAiLabelBuffers(player, mapOf("x" to 30.0), UPDATED_AT)

    database.clearAiLabelBuffers(player) { true }

    assertTrue(database.loadAiLabelBuffers(player).isEmpty())
    assertNull(database.loadAiBuffer(player))
  }

  @Test
  fun `clearing the score keeps the history`() {
    val database = freshDatabase("score-clear")
    val player = UUID.randomUUID()
    database.saveMitigationScore(player, StoredScore(12.0, UPDATED_AT, 3, 2, 7L))

    database.clearMitigationScore(player, UPDATED_AT + 1)

    assertEquals(StoredScore(0.0, UPDATED_AT + 1, 3, 2, 7L), database.loadMitigationScore(player))
  }

  @Test
  fun `clearing a score that was never stored writes nothing`() {
    val database = freshDatabase("score-clear-none")
    val player = UUID.randomUUID()

    database.clearMitigationScore(player, UPDATED_AT)

    assertNull(database.loadMitigationScore(player))
  }

  @Test
  fun `a page far past the end is empty instead of overflowing`() {
    val database = freshDatabase("page-overflow")

    assertTrue(database.getViolations(Int.MAX_VALUE, 100, 0L).isEmpty())
    assertTrue(database.getViolations(UUID.randomUUID(), Int.MAX_VALUE, 100).isEmpty())
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
      SqlViolationDatabase(Database.connect(jdbcUrl, "org.sqlite.JDBC"))
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
    return SqlViolationDatabase(Database.connect(jdbcUrl, "org.sqlite.JDBC"))
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
