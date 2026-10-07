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
import io.mockk.every
import io.mockk.mockk
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResilientViolationDatabaseTest {
  private class Switchable(val store: ViolationDatabase) : ViolationDatabase by store {
    @Volatile var failure: Exception? = null

    val database: ViolationDatabase
      get() = this

    override fun recordFlag(playerUUID: UUID, punishGroupName: String, at: Long, since: Long): Int {
      failure?.let { throw it }
      return store.recordFlag(playerUUID, punishGroupName, at, since)
    }
  }

  private fun memory(): ViolationDatabase {
    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.config.getString("history.server-name", any()) } returns "server"
    return InMemoryViolationDatabase()
  }

  private val player = UUID.randomUUID()

  @Test
  fun `a lost connection is written later instead of being lost for good`() {
    val primary = Switchable(memory())
    val health = DatabaseHealth()
    val database =
      ResilientViolationDatabase(primary.database, memory(), health, Logger.getAnonymousLogger())

    primary.failure = SQLTransientConnectionException("pool timeout")
    database.recordFlag(player, "general", 1L, 0L)
    database.recordFlag(player, "general", 2L, 0L)
    assertFalse(health.isPersistentAvailable())
    assertEquals(2, database.pendingWrites)

    assertFalse(database.recover(), "the database is still down")

    primary.failure = null
    assertTrue(database.recover())
    assertTrue(health.isPersistentAvailable())
    assertEquals(0, database.pendingWrites)
    assertEquals(3, primary.store.recordFlag(player, "general", 3L, 0L))
  }

  @Test
  fun `a broken query is answered from memory without leaving the database`() {
    val primary = Switchable(memory())
    val health = DatabaseHealth()
    val database =
      ResilientViolationDatabase(primary.database, memory(), health, Logger.getAnonymousLogger())

    primary.failure = SQLSyntaxErrorException("unknown column")
    database.recordFlag(player, "general", 1L, 0L)

    assertTrue(health.isPersistentAvailable())
    assertEquals(0, database.pendingWrites)
    primary.failure = null
    assertEquals(1, database.recordFlag(player, "general", 2L, 0L))
  }

  @Test
  fun `a broken query reaches a strict caller instead of being answered from memory`() {
    val primary = Switchable(memory())
    val health = DatabaseHealth()
    val database =
      ResilientViolationDatabase(primary.database, memory(), health, Logger.getAnonymousLogger())

    primary.failure = SQLSyntaxErrorException("unknown column")
    assertFailsWith<SQLSyntaxErrorException> {
      strictStorage { database.recordFlag(player, "general", 1L, 0L) }
    }

    assertTrue(health.isPersistentAvailable())
    assertEquals(0, database.pendingWrites)
    assertEquals(
      1,
      database.recordFlag(player, "general", 2L, 0L),
      "outside strict mode it falls back",
    )
  }

  @Test
  fun `a strict caller still falls back when the connection is lost`() {
    val primary = Switchable(memory())
    val health = DatabaseHealth()
    val database =
      ResilientViolationDatabase(primary.database, memory(), health, Logger.getAnonymousLogger())

    primary.failure = SQLTransientConnectionException("pool timeout")
    strictStorage { database.recordFlag(player, "general", 1L, 0L) }

    assertFalse(health.isPersistentAvailable())
    assertEquals(1, database.pendingWrites)
  }

  @Test
  fun `the oldest changes are dropped once the queue is full`() {
    val primary = Switchable(memory())
    val database =
      ResilientViolationDatabase(
        primary.database,
        memory(),
        DatabaseHealth(),
        Logger.getAnonymousLogger(),
        maxPending = 2,
      )

    primary.failure = SQLTransientConnectionException("down")
    repeat(5) { database.recordFlag(player, "general", it.toLong(), 0L) }

    assertEquals(2, database.pendingWrites)
  }
}
