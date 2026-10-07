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
package ac.shard.player

import ac.shard.detection.AiSnapshotStore
import ac.shard.detection.PersistentBufferService
import ac.shard.mitigation.MitigationLogStore
import ac.shard.mitigation.MitigationScoreStore
import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Test

class PlayerPersistenceTest {
  private val buffers = mockk<PersistentBufferService>(relaxed = true)
  private val scores = mockk<MitigationScoreStore>(relaxed = true)
  private val log = mockk<MitigationLogStore>(relaxed = true)
  private val snapshots = mockk<AiSnapshotStore>(relaxed = true)
  private val queued = mutableListOf<Runnable>()
  private val scheduler =
    mockk<SchedulerService>(relaxed = true) {
      every { runAsync(any<Runnable>()) } answers
        {
          queued += firstArg<Runnable>()
          mockk(relaxed = true)
        }
    }
  private val persistence =
    PlayerPersistence(buffers, scores, log, snapshots, mockk(relaxed = true), scheduler)

  private fun player() = mockk<ShardPlayer> { every { persisted } returns AtomicBoolean(false) }

  private fun savedOnce(player: ShardPlayer) {
    verify(exactly = 1) { buffers.saveOnQuit(player) }
    verify(exactly = 1) { scores.save(player) }
    verify(exactly = 1) { log.saveOnQuit(player) }
    verify(exactly = 1) { snapshots.saveOnQuit(player) }
  }

  @Test
  fun `a player both attached and leaving is saved once`() {
    val player = player()

    persistence.saveLater(player)
    persistence.saveAll(listOf(player))
    queued.forEach(Runnable::run)

    savedOnce(player)
  }

  @Test
  fun `a quit whose task never started is saved at shutdown`() {
    val player = player()

    persistence.saveLater(player)
    persistence.saveAll(emptyList())

    savedOnce(player)
  }

  @Test
  fun `a late quit after shutdown writes nothing more`() {
    val player = player()

    persistence.saveAll(listOf(player))
    persistence.saveLater(player)
    queued.forEach(Runnable::run)

    savedOnce(player)
  }
}
