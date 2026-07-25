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
package ac.shard.monitor.core

import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.bukkit.entity.Player

class ScoreboardSlotObserverTest {
  private val scheduler = mockk<SchedulerService>(relaxed = true)
  private val viewerId = UUID.randomUUID()

  private fun registryWithClaim(slot: Int, objective: String): ScoreboardSlotRegistry {
    val registry = ScoreboardSlotRegistry()
    registry.claim(viewerId, SlotClaim(slot, objective) { _, _, _ -> })
    return registry
  }

  @Test
  fun `an idle registry short-circuits before the packet type is read`() {
    val event = mockk<PacketSendEvent>()

    ScoreboardSlotObserver(scheduler, ScoreboardSlotRegistry()).onPacketSend(event)
  }

  @Test
  fun `an unrelated packet type is ignored before the player is resolved`() {
    val event = mockk<PacketSendEvent>()
    every { event.packetType } returns PacketType.Play.Server.SYSTEM_CHAT_MESSAGE

    ScoreboardSlotObserver(scheduler, registryWithClaim(2, "obj")).onPacketSend(event)
  }

  @Test
  fun `send event without bukkit player is ignored`() {
    val event = mockk<PacketSendEvent>()
    every { event.packetType } returns PacketType.Play.Server.DISPLAY_SCOREBOARD
    every { event.getPlayer<Any>() } returns Any()

    ScoreboardSlotObserver(scheduler, registryWithClaim(2, "obj")).onPacketSend(event)
  }

  @Test
  fun `a viewer without claims is ignored`() {
    val viewer = mockk<Player>(relaxed = true)
    every { viewer.uniqueId } returns UUID.randomUUID()
    val event = mockk<PacketSendEvent>()
    every { event.packetType } returns PacketType.Play.Server.DISPLAY_SCOREBOARD
    every { event.getPlayer<Any>() } returns viewer

    ScoreboardSlotObserver(scheduler, registryWithClaim(2, "obj")).onPacketSend(event)
  }

  @Test
  fun `registry stays busy while a claim is held`() {
    assertTrue(!registryWithClaim(2, "obj").isIdle())
  }
}
