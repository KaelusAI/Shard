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
import ac.shard.ai.AiResult
import ac.shard.ai.AiService
import ac.shard.alert.AlertManager
import ac.shard.api.event.ShardEventBus
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.player.ShardPlayer
import ac.shard.player.state.CombatState
import ac.shard.punishment.PunishmentManager
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIResponse
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.Method
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class AiCheckResponseTest {

  @Test
  fun `probability debug log uses fixed formatting only when enabled`() {
    val fixture =
      createFixture(debugEnabled = true, enabledCategories = setOf(DebugCategory.AI_PROBABILITY))

    fixture.invokeOnResponse(0.42)

    verify(exactly = 1) {
      fixture.logger.info(
        "[DEBUG | AI_PROBABILITY] [TestPlayer | 1.21.4] Prob: 0.4200 | Buffer: 0.00 -> 0.00 | Damage Multiplier: 1.00"
      )
    }
  }

  @Test
  fun `flag debug string uses compact fixed formatting`() {
    val fixture = createFixture(aiFlag = 0.5, bufferMultiplier = 20.0)

    fixture.invokeOnResponse(0.95)

    verify(exactly = 1) {
      fixture.punishmentManager.handleFlag(fixture.check, "prob=0.95 buffer=1.0")
    }
    assertEquals(0.0, fixture.check.buffer)
  }

  @Test
  fun `ai formatting helpers use stable decimal output`() {
    assertEquals("1.0", formatAiBuffer(1.04))
    assertEquals("0.95", formatAiProbability(0.945))
    assertEquals("0.4200", formatAiProbabilityVerbose(0.42))
  }

  private fun createFixture(
    debugEnabled: Boolean = false,
    enabledCategories: Set<DebugCategory> = emptySet(),
    aiFlag: Double = 10.0,
    bufferMultiplier: Double = 1.0,
  ): Fixture {
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns logger

    val aiService = mockk<AiService>(relaxed = true)
    every { aiService.isEnabled } returns true

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiPreWindow } returns 20
    every { configManager.aiPostWindow } returns 40
    every { configManager.aiStep } returns 1
    every { configManager.aiFlag } returns aiFlag
    every { configManager.aiResetOnFlag } returns 0.0
    every { configManager.aiBufferMultiplier } returns bufferMultiplier
    every { configManager.aiBufferDecrease } returns 0.25
    every { configManager.suspiciousAlertsBuffer } returns 25.0
    every { configManager.enabledDebugCategories } returns enabledCategories
    every { configManager.isDebugEnabled() } returns debugEnabled

    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"
    every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")

    val eventBus = mockk<ShardEventBus>(relaxed = true)
    val punishmentManager = mockk<PunishmentManager>(relaxed = true)
    val combat = CombatState()

    val user = mockk<User>(relaxed = true)
    every { user.clientVersion } returns ClientVersion.V_1_21_4

    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.uuid } returns player.uniqueId
    every { shardPlayer.eventBus } returns eventBus
    every { shardPlayer.punishmentManager } returns punishmentManager
    every { shardPlayer.combat } returns combat
    every { shardPlayer.user } returns user

    val check =
      AiCheck(
        shardPlayer = shardPlayer,
        plugin = plugin,
        aiService = aiService,
        configManager = configManager,
        regionProvider = mockk<RegionProvider>(relaxed = true),
        alertManager = mockk<AlertManager>(relaxed = true),
        damageProcessor = mockk<DamageProcessor>(relaxed = true),
        debugManager = DebugManager(plugin, configManager),
        scheduler = mockk<SchedulerService>(relaxed = true),
      )

    return Fixture(check, logger, punishmentManager)
  }

  private data class Fixture(
    val check: AiCheck,
    val logger: Logger,
    val punishmentManager: PunishmentManager,
  ) {
    fun invokeOnResponse(probability: Double) {
      onResponseMethod.invoke(
        check,
        AiResult(AIResponse(probability), """{"probability":$probability}""", null, false),
      )
    }
  }

  private companion object {
    val onResponseMethod: Method =
      AiCheck::class.java.getDeclaredMethod("onResponse", AiResult::class.java).apply {
        isAccessible = true
      }
  }
}
