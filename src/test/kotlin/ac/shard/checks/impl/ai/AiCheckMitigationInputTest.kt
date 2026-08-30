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
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelThresholds
import ac.shard.alert.AlertManager
import ac.shard.api.event.ShardEventBus
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationScorer
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
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class AiCheckMitigationInputTest {

  @Test
  fun `a softmax model that decided the player is clean must not feed mitigation`() {
    val fixture =
      fixture(
        labels = listOf("clean", "aim"),
        mode = LabelMode.MULTI_CLASS,
        legit = setOf("clean"),
        thresholds = mapOf("aim" to LabelThresholds(0.9, 0.1)),
      )

    fixture.respond(probability = 0.94, probabilities = listOf(0.94, 0.06))

    assertTrue(
      fixture.check.lastCheatProbability < 0.2,
      "0.94 is the model's confidence that the player is clean, and mitigating on it would " +
        "throttle the whole server",
    )
  }

  @Test
  fun `mitigation follows the strongest cheat label, not the scalar the server happened to send`() {
    val fixture = fixture(labels = listOf("aim", "trigger"), mode = LabelMode.MULTI_LABEL)

    fixture.respond(probability = 0.40, probabilities = listOf(0.35, 0.96))

    assertEquals(0.96, fixture.check.lastCheatProbability, 1e-9)
    assertEquals(0.40, fixture.check.lastProbability, 1e-9, "what is shown stays the server's own")
  }

  @Test
  fun `a model without labels still mitigates on its single number`() {
    val fixture = fixture(labels = emptyList(), mode = null)

    fixture.respond(probability = 0.93, probabilities = null)

    assertEquals(0.93, fixture.check.lastCheatProbability, 1e-9)
  }

  @Test
  fun `a window the resolver threw away is not scored as a calm one`() {
    val fixture = fixture(labels = listOf("aim", "trigger"), mode = LabelMode.MULTI_LABEL)

    fixture.respond(probability = 0.97, probabilities = listOf(0.95, 0.93))
    val scored = fixture.check.lastCheatProbability

    fixture.respond(probability = 0.97, probabilities = listOf(0.95))

    assertEquals(
      scored,
      fixture.check.lastCheatProbability,
      1e-9,
      "the answer carries one probability for two labels, so the resolver drops the window. " +
        "Scoring it anyway means a zero reaches mitigation and wipes every accumulated hold",
    )
    verify(exactly = 1) { fixture.scorer.record(any(), any(), any()) }
  }

  private fun fixture(
    labels: List<String>,
    mode: LabelMode?,
    legit: Set<String> = emptySet(),
    thresholds: Map<String, LabelThresholds> = emptyMap(),
  ): Fixture {
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns mockk<Logger>(relaxed = true)

    val aiService = mockk<AiService>(relaxed = true)
    every { aiService.isEnabled } returns true

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiPreWindow } returns 20
    every { configManager.aiPostWindow } returns 40
    every { configManager.aiStep } returns 1
    every { configManager.aiFlag } returns 1_000.0
    every { configManager.aiResetOnFlag } returns 0.0
    every { configManager.aiBufferMultiplier } returns 1.0
    every { configManager.aiBufferDecrease } returns 0.25
    every { configManager.suspiciousAlertsBuffer } returns 25.0
    every { configManager.aiLabelMaxTracked } returns 32
    every { configManager.aiLabelSplit } returns true
    every { configManager.aiLabels } returns labels
    every { configManager.effectiveLabelMode } returns mode
    every { configManager.aiLegitLabels } returns legit
    every { configManager.aiLabelThresholds } returns thresholds
    every { configManager.enabledDebugCategories } returns emptySet<DebugCategory>()

    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"
    every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000002")

    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.uuid } returns player.uniqueId
    every { shardPlayer.eventBus } returns mockk<ShardEventBus>(relaxed = true)
    every { shardPlayer.punishmentManager } returns mockk<PunishmentManager>(relaxed = true)
    every { shardPlayer.combat } returns CombatState()
    every { shardPlayer.user } returns
      mockk<User>(relaxed = true).also { every { it.clientVersion } returns ClientVersion.V_1_21_4 }

    val scorer = mockk<MitigationScorer>(relaxed = true)
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
        mitigationScorer = scorer,
      )
    return Fixture(check, scorer)
  }

  private class Fixture(val check: AiCheck, val scorer: MitigationScorer) {
    fun respond(probability: Double, probabilities: List<Double>?) {
      val response = AIResponse(probability, null, probabilities)
      onResponse.invoke(check, AiResult(response, "{}", null, false))
    }
  }

  private companion object {
    val onResponse: Method =
      AiCheck::class.java.getDeclaredMethod("onResponse", AiResult::class.java).apply {
        isAccessible = true
      }
  }
}
