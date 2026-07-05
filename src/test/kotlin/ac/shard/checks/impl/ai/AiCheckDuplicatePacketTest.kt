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
import ac.shard.ai.AiService
import ac.shard.alert.AlertManager
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.data.PacketStateData
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.Field
import java.util.logging.Logger
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiCheckDuplicatePacketTest {

  @Test
  fun `duplicate flying packet is ignored before ai request`() {
    val fixture = createFixture()

    fixture.check.onPacketReceive(fixture.event)

    verify(exactly = 0) { fixture.aiService.request(any(), any()) }
  }

  @Test
  fun `duplicate flying packet does not log to console when debug is disabled`() {
    val fixture = createFixture()

    fixture.check.onPacketReceive(fixture.event)

    verify(exactly = 0) { fixture.logger.info(any<String>()) }
  }

  @Test
  fun `duplicate flying packet does not enter ai tick sequence`() {
    val fixture = createFixture()

    fixture.check.onPacketReceive(fixture.event)

    assertEquals(0, fixture.tickSequenceSize())
  }

  @Test
  fun `duplicate flying packet logs only when packet duplication debug is enabled`() {
    val fixture =
      createFixture(
        debugEnabled = true,
        enabledCategories = setOf(DebugCategory.PACKET_DUPLICATION),
      )

    fixture.check.onPacketReceive(fixture.event)

    verify(exactly = 1) {
      fixture.logger.info(
        match<String> { message ->
          message.contains("[DEBUG | PACKET_DUPLICATION]") &&
            message.contains("Mojang failed IQ Test for: TestPlayer.")
        }
      )
    }
  }

  private fun createFixture(
    debugEnabled: Boolean = false,
    enabledCategories: Set<DebugCategory> = emptySet(),
  ): Fixture {
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns logger

    val aiService = mockk<AiService>(relaxed = true)
    every { aiService.isEnabled } returns true

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiSequence } returns 40
    every { configManager.aiStep } returns 1
    every { configManager.aiFlag } returns 1.0
    every { configManager.aiResetOnFlag } returns 0.0
    every { configManager.aiBufferMultiplier } returns 1.0
    every { configManager.aiBufferDecrease } returns 0.25
    every { configManager.suspiciousAlertsBuffer } returns 25.0
    every { configManager.enabledDebugCategories } returns enabledCategories
    every { configManager.isDebugEnabled() } returns debugEnabled

    val packetStateData = PacketStateData().apply { lastPacketWasOnePointSeventeenDuplicate = true }
    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"

    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.packetStateData } returns packetStateData

    val event = mockk<PacketReceiveEvent>(relaxed = true)
    every { event.packetType } returns PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION

    val debugManager = DebugManager(plugin, configManager)
    val check =
      AiCheck(
        shardPlayer = shardPlayer,
        plugin = plugin,
        aiService = aiService,
        configManager = configManager,
        regionProvider = mockk<RegionProvider>(relaxed = true),
        alertManager = mockk<AlertManager>(relaxed = true),
        damageProcessor = mockk<DamageProcessor>(relaxed = true),
        debugManager = debugManager,
        scheduler = mockk<SchedulerService>(relaxed = true),
      )

    return Fixture(check, aiService, event, logger)
  }

  private data class Fixture(
    val check: AiCheck,
    val aiService: AiService,
    val event: PacketReceiveEvent,
    val logger: Logger,
  ) {
    fun tickSequenceSize(): Int {
      val ring = ringField.get(check) as TickRingBuffer
      return ring.count
    }
  }

  private companion object {
    val ringField: Field =
      AiCheck::class.java.getDeclaredField("ring").apply { isAccessible = true }
  }
}
