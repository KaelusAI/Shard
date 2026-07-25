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

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class ViewTeamConflictObserverTest {
  @Test
  fun `a non-team packet never reaches the session lookup`() {
    val coordinator = mockk<ViewSessionCoordinator>(relaxed = true)
    val event = mockk<PacketSendEvent>()
    every { event.packetType } returns PacketType.Play.Server.SYSTEM_CHAT_MESSAGE

    ViewTeamConflictObserver(coordinator).onPacketSend(event)

    verify(exactly = 0) { coordinator.session(any()) }
  }

  @Test
  fun `send event without bukkit player is ignored`() {
    val coordinator = mockk<ViewSessionCoordinator>(relaxed = true)
    val event = mockk<PacketSendEvent>()
    every { event.packetType } returns PacketType.Play.Server.TEAMS
    every { event.getPlayer<Any>() } returns Any()

    ViewTeamConflictObserver(coordinator).onPacketSend(event)

    verify(exactly = 0) { coordinator.session(any()) }
  }
}
