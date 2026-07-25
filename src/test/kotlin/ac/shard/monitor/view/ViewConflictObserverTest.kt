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
package ac.shard.monitor.view

import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.event.PacketSendEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test

class ViewConflictObserverTest {
  @Test
  fun `send event without bukkit player is ignored`() {
    val scheduler = mockk<SchedulerService>(relaxed = true)
    val coordinator = mockk<ViewSessionCoordinator>(relaxed = true)
    val belowNameConflicts = mockk<ViewBelowNameConflictCoordinator>(relaxed = true)
    val event = mockk<PacketSendEvent>()
    every { event.getPlayer<Any>() } returns Any()

    val observer =
      ViewConflictObserver(scheduler, coordinator, belowNameConflicts) { _: UUID -> null }

    observer.onPacketSend(event)

    verify(exactly = 0) { coordinator.session(any()) }
  }
}
