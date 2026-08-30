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
package ac.shard.checks.impl.ai

import ac.shard.config.MitigationsFile
import ac.shard.database.DatabaseManager
import ac.shard.database.InMemoryViolationDatabase
import ac.shard.mitigation.MitigationState
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AiSnapshotStoreTest {

  private val scoring = MitigationsFile.DEFAULT_SCORE
  private val uuid: UUID = UUID.randomUUID()
  private val now = 1_786_000_000_000L

  private class Fixture(
    val store: AiSnapshotStore,
    val database: InMemoryViolationDatabase,
    val state: MitigationState,
    val player: ShardPlayer,
  )

  private fun fixture(prob90: Int = 0): Fixture {
    val state = MitigationState()
    val aiCheck = mockk<AiCheck>(relaxed = true) { every { this@mockk.prob90 } returns prob90 }
    val player =
      mockk<ShardPlayer>(relaxed = true) {
        every { mitigation } returns state
        every { this@mockk.uuid } returns this@AiSnapshotStoreTest.uuid
        every { checkManager.getCheck(AiCheck::class.java) } returns aiCheck
      }
    val database = InMemoryViolationDatabase(mockk(relaxed = true))
    val databaseManager =
      mockk<DatabaseManager>(relaxed = true) { every { this@mockk.database } returns database }
    return Fixture(AiSnapshotStore(databaseManager) { now }, database, state, player)
  }

  @Test
  fun `quitting stores the windows the model actually answered`() {
    val fixture = fixture(prob90 = 7)
    repeat(4) { fixture.state.record(1.0, 0.95, now, scoring) }
    repeat(6) { fixture.state.record(-0.5, 0.02, now, scoring) }

    fixture.store.saveOnQuit(fixture.player)

    val snapshot = assertNotNull(fixture.database.loadAiSnapshot(uuid))
    assertEquals(10L, snapshot.windows)
    assertEquals(7L, snapshot.highWindows)
    assertEquals(now, snapshot.savedAt)
    assertEquals(6L, snapshot.low, "six answers landed in the low tail")
    assertEquals(4L, snapshot.high, "four answers landed above the spike mark")
  }

  @Test
  fun `a player the model never answered for leaves nothing behind`() {
    val fixture = fixture()

    fixture.store.saveOnQuit(fixture.player)

    assertNull(fixture.database.loadAiSnapshot(uuid))
  }
}
