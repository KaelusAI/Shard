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
package ac.shard.punishment

import ac.shard.Shard
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.detection.DetectionState
import ac.shard.mitigation.MitigationState
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertTrue

class FlagSnapshotTest {

  @Test
  fun `the buffer is read before handleFlag returns, not on the coroutine`() {
    var reads = 0
    val aiCheck =
      mockk<DetectionState>(relaxed = true) {
        every { buffer } answers
          {
            reads++
            62.4
          }
      }

    val pending = mutableListOf<Runnable>()
    val scheduler =
      mockk<SchedulerService>(relaxed = true) {
        every { runAsync(any<Runnable>()) } answers
          {
            pending += firstArg<Runnable>()
            mockk(relaxed = true)
          }
      }

    val shardPlayer =
      mockk<ShardPlayer>(relaxed = true) {
        every { mitigation } returns MitigationState()
        every { uuid } returns UUID.randomUUID()
        every { detection } returns aiCheck
        every { exemptManager.isExempt(any<ShardPlayer>()) } returns false
        every { exemptManager.isDisabled(any<ShardPlayer>()) } returns false
      }

    val manager =
      PunishmentManager(
        messages = mockk(relaxed = true),
        plugin = mockk<Shard>(relaxed = true),
        logger = Logger.getLogger("test"),
        configManager = mockk<ConfigManager>(relaxed = true),
        databaseManager = mockk<DatabaseManager>(relaxed = true),
        alertManager = mockk(relaxed = true),
        adventure = mockk(relaxed = true),
        scheduler = scheduler,
        events = mockk(relaxed = true),
        mitigationSkip = mockk(relaxed = true),
      )

    manager.handleFlag(shardPlayer, "AI", emptySet(), "debug")

    assertTrue(
      reads > 0,
      "handleFlag returned without reading the buffer, so the snapshot is taken later - by then " +
        "detection has already reset it and the violation row stores the reset value",
    )
    assertTrue(pending.isEmpty() || reads > 0, "nothing else can explain the read")
  }
}
