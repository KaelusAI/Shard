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

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.entity.Player

class ScoreboardPacketBridgeTest {
  private val viewer = mockk<Player>(relaxed = true)
  private val playerManager = mockk<PlayerManager>(relaxed = true)
  private val sent = mutableListOf<PacketWrapper<*>>()

  private fun install(server: ServerVersion, client: ClientVersion) {
    val serverManager = mockk<ServerManager>(relaxed = true)
    every { serverManager.version } returns server
    every { playerManager.getClientVersion(viewer) } returns client
    val packet = slot<PacketWrapper<*>>()
    every { playerManager.sendPacket(viewer, capture(packet)) } answers
      {
        sent.add(packet.captured)
      }

    val api = mockk<PacketEventsAPI<*>>(relaxed = true)
    every { api.serverManager } returns serverManager
    every { api.playerManager } returns playerManager
    mockkStatic(PacketEvents::class)
    every { PacketEvents.getAPI() } returns api
  }

  @AfterTest
  fun tearDown() {
    io.mockk.unmockkStatic(PacketEvents::class)
    sent.clear()
  }

  @Test
  fun `modern server and modern client get the score-format packet`() {
    install(ServerVersion.V_1_21, ClientVersion.V_1_21)
    val bridge = ScoreboardPacketBridge(ComponentCache())

    assertTrue(bridge.supportsFancyText(viewer))
    bridge.updateEntry(viewer, "obj", "entry", 7, ScoreDisplay(null, "<white>x</white>"))

    val packet = assertIs<WrapperPlayServerUpdateScore>(sent.single())
    assertEquals(7, packet.value.orElse(-1))
  }

  @Test
  fun `legacy client on a modern server falls back to the numeric packet`() {
    install(ServerVersion.V_1_21, ClientVersion.V_1_20_2)
    val bridge = ScoreboardPacketBridge(ComponentCache())

    assertFalse(bridge.supportsFancyText(viewer))
    bridge.updateEntry(viewer, "obj", "entry", 7, ScoreDisplay(null, "<white>x</white>"))

    val packet = assertIs<WrapperPlayServerUpdateScore>(sent.single())
    assertEquals(7, packet.value.orElse(-1))
    assertNull(packet.entityDisplayName)
  }

  @Test
  fun `legacy server keeps the numeric packet even for a modern client`() {
    install(ServerVersion.V_1_20_1, ClientVersion.V_1_21)
    val bridge = ScoreboardPacketBridge(ComponentCache())

    assertFalse(bridge.supportsFancyText(viewer))
  }

  @Test
  fun `entry removal uses reset-score only when both sides are modern`() {
    install(ServerVersion.V_1_21, ClientVersion.V_1_21)
    ScoreboardPacketBridge(ComponentCache()).removeEntry(viewer, "obj", "entry")

    assertIs<WrapperPlayServerResetScore>(sent.single())
  }

  @Test
  fun `entry removal falls back to the remove action for a legacy client`() {
    install(ServerVersion.V_1_21, ClientVersion.V_1_20_2)
    ScoreboardPacketBridge(ComponentCache()).removeEntry(viewer, "obj", "entry")

    val packet = assertIs<WrapperPlayServerUpdateScore>(sent.single())
    assertEquals(WrapperPlayServerUpdateScore.Action.REMOVE_ITEM, packet.action)
  }
}
