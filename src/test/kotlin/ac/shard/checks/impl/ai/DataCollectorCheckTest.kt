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

import ac.shard.Shard
import ac.shard.checks.CheckManager
import ac.shard.config.ConfigManager
import ac.shard.data.DataSession
import ac.shard.entity.CompensatedEntities
import ac.shard.entity.types.PacketEntitySelf
import ac.shard.player.ShardPlayer
import ac.shard.utils.data.PacketStateData
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.logging.Logger
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class DataCollectorCheckTest {

  @Test
  fun `duplicate flying packet does not enter data collection`() {
    val fixture = createFixture()
    fixture.packetStateData.lastPacketWasOnePointSeventeenDuplicate = true

    fixture.check.onPacketReceive(fixture.event)

    verify(exactly = 0) { fixture.session.addTick(any()) }
  }

  @Test
  fun `riding player does not enter data collection`() {
    val fixture = createFixture(riding = true)

    fixture.check.onPacketReceive(fixture.event)

    verify(exactly = 0) { fixture.session.addTick(any()) }
  }

  @Test
  fun `normal flying packet enters data collection`() {
    val fixture = createFixture()

    fixture.check.onPacketReceive(fixture.event)

    verify(exactly = 1) { fixture.session.addTick(any()) }
  }

  private fun createFixture(riding: Boolean = false): Fixture {
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns logger

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiContinuous } returns true

    val packetStateData = PacketStateData()
    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"

    val checkManager = mockk<CheckManager>(relaxed = true)

    val self = mockk<PacketEntitySelf>(relaxed = true)
    every { self.riding } returns if (riding) mockk(relaxed = true) else null
    val compensatedEntities = mockk<CompensatedEntities>(relaxed = true)
    every { compensatedEntities.self } returns self

    val uuid = UUID.randomUUID()
    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.uuid } returns uuid
    every { shardPlayer.packetStateData } returns packetStateData
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.compensatedEntities } returns compensatedEntities

    val session = mockk<DataSession>(relaxed = true)
    val dataCollectorManager = mockk<DataCollectorManager>(relaxed = true)
    every { dataCollectorManager.getSession(uuid) } returns session

    val event = mockk<PacketReceiveEvent>(relaxed = true)
    every { event.packetType } returns PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION

    val check =
      DataCollectorCheck(
        shardPlayer = shardPlayer,
        dataCollectorManager = dataCollectorManager,
        plugin = plugin,
        configManager = configManager,
      )

    return Fixture(check, session, event, packetStateData)
  }

  private data class Fixture(
    val check: DataCollectorCheck,
    val session: DataSession,
    val event: PacketReceiveEvent,
    val packetStateData: PacketStateData,
  )
}
