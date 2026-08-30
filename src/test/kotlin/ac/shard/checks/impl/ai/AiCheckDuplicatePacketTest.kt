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
import ac.shard.data.TickBuffer
import ac.shard.data.TickData
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationScorer
import ac.shard.player.ShardPlayer
import ac.shard.player.state.TrackingState
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import java.util.logging.Logger
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiCheckDuplicatePacketTest {

  @Test
  fun `attack tick anchors and advances the inference window`() {
    val fixture = createFixture(attackThisTick = true)

    fixture.check.onDataTick(fixture.shardPlayer)

    assertEquals(1, fixture.ticksSinceAttackMark())
  }

  @Test
  fun `tick without an attack does not anchor the window`() {
    val fixture = createFixture(attackThisTick = false)

    fixture.check.onDataTick(fixture.shardPlayer)

    assertEquals(-1, fixture.ticksSinceAttackMark())
  }

  @Test
  fun `a data buffer swap invalidates a stale anchor`() {
    val fixture = createFixture(attackThisTick = true)

    fixture.check.onDataTick(fixture.shardPlayer)
    assertEquals(1, fixture.ticksSinceAttackMark())

    fixture.tracking.attackThisTick = false
    fixture.tracking.windowStartThisTick = false
    every { fixture.shardPlayer.tickBuffer } returns mockk<TickBuffer>(relaxed = true)
    fixture.check.onDataTick(fixture.shardPlayer)

    assertEquals(-1, fixture.ticksSinceAttackMark())
  }

  private fun createFixture(attackThisTick: Boolean): Fixture {
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns logger

    val aiService = mockk<AiService>(relaxed = true)
    every { aiService.isEnabled } returns true

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiPreWindow } returns 20
    every { configManager.aiPostWindow } returns 40
    every { configManager.aiStep } returns 60
    every { configManager.aiFlag } returns 1.0
    every { configManager.aiResetOnFlag } returns 0.0
    every { configManager.aiBufferMultiplier } returns 1.0
    every { configManager.aiBufferDecrease } returns 1.0
    every { configManager.suspiciousAlertsBuffer } returns 25.0
    every { configManager.aiLabelMaxTracked } returns 32
    every { configManager.aiLabelSplit } returns true
    every { configManager.aiLabels } returns emptyList()

    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"

    val tracking =
      TrackingState().apply {
        this.attackThisTick = attackThisTick
        if (attackThisTick) raiseWindowStart(TickData.START_MELEE_PLAYER)
      }
    val tickBuffer = mockk<TickBuffer>(relaxed = true)
    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.tracking } returns tracking
    every { shardPlayer.tickBuffer } returns tickBuffer
    every { shardPlayer.compensatedEntities.self.riding } returns null

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
        mitigationScorer = mockk<MitigationScorer>(relaxed = true),
      )

    return Fixture(check, shardPlayer, tracking)
  }

  private data class Fixture(
    val check: AiCheck,
    val shardPlayer: ShardPlayer,
    val tracking: TrackingState,
  ) {
    fun ticksSinceAttackMark(): Int = check.inferenceTicks
  }
}
