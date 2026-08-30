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
import ac.shard.ai.label.LabelledVerdict
import ac.shard.alert.AlertManager
import ac.shard.api.event.ShardEventBus
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationScorer
import ac.shard.player.ShardPlayer
import ac.shard.player.state.CombatState
import ac.shard.punishment.PunishmentManager
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class AiCheckLabelBuffersTest {

  @Test
  fun `two labels grow their own buffers independently`() {
    val check = createCheck()

    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 1.0)))

    assertEquals(5.0, check.labelBuffer("aim"), 1e-9)
    assertEquals(10.0, check.labelBuffer("trigger"), 1e-9)
    assertEquals(10.0, check.buffer, 1e-9, "overall is the highest label, not their sum")
  }

  @Test
  fun `only the label that crossed the threshold is reported and reset`() {
    val check = createCheck(flag = 8.0)

    val crossed = check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 1.0)))

    assertEquals(setOf("trigger"), crossed.keys)
    assertEquals(5.0, check.labelBuffer("aim"), 1e-9, "aim was below the flag and keeps its buffer")
    assertEquals(0.0, check.labelBuffer("trigger"), 1e-9)
  }

  @Test
  fun `a label the model stopped sending decays instead of hanging forever`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))
    assertEquals(5.0, check.labelBuffer("trigger"), 1e-9)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(4.0, check.labelBuffer("trigger"), 1e-9)
    assertEquals(10.0, check.labelBuffer("aim"), 1e-9)
  }

  @Test
  fun `a decayed label leaves the map so the overall buffer follows the live ones`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.92, "trigger" to 0.92)))

    repeat(3) { check.feedBuffers(attributed(mapOf("aim" to 0.92))) }

    assertTrue("trigger" !in check.labelBufferSnapshot(), "a drained label must not linger")
    assertEquals(check.labelBuffer("aim"), check.buffer, 1e-9)
  }

  @Test
  fun `a fallback verdict freezes the other buffers instead of draining them`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    check.feedBuffers(LabelledVerdict(mapOf("_unattributed" to 0.5), false))

    assertEquals(5.0, check.labelBuffer("aim"), 1e-9)
    assertEquals(5.0, check.labelBuffer("trigger"), 1e-9)
  }

  @Test
  fun `a label scored near zero drains exactly as if it had been left out`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.05)))

    assertEquals(
      4.0,
      check.labelBuffer("trigger"),
      1e-9,
      "a multiclass window the model calls clean now names the class instead of omitting it, " +
        "and naming it has to drain the same amount omitting it did",
    )
  }

  @Test
  fun `a label scored between the two thresholds holds its buffer instead of moving it`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.40)))

    assertEquals(
      5.0,
      check.labelBuffer("trigger"),
      1e-9,
      "the band between the thresholds is where the model says it does not know, and an " +
        "undecided window is not evidence of innocence any more than of guilt",
    )
  }

  @Test
  fun `a restored buffer from a retired label drains once the model reports again`() {
    val check = createCheck()
    check.restoreLabelBuffer("reach", 3.0)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(2.0, check.labelBuffer("reach"), 1e-9)
  }

  @Test
  fun `a label the model dropped from its set goes at once, not over dozens of windows`() {
    val check = createCheck(declared = listOf("aim"))
    check.restoreLabelBuffer("trigger", 49.0)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertTrue(
      "trigger" !in check.labelBufferSnapshot(),
      "the model can never speak to that label again, so decaying it just keeps the player listed",
    )
    assertEquals(5.0, check.buffer, 1e-9, "the overall buffer follows the labels that are left")
  }

  @Test
  fun `a scalar carried over from a single-headed model decays, it is not dropped`() {
    val check = createCheck(declared = listOf("aim"))
    check.restoreBuffer(4.0)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(
      3.0,
      check.labelBuffer(AiCheck.UNATTRIBUTED_LABEL),
      1e-9,
      "the scalar was earned honestly, so it drains rather than vanishing",
    )
  }

  @Test
  fun `going back to a single-headed model keeps feeding the label it still declares`() {
    val check = createCheck(declared = listOf("aim", "trigger"))
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    val single = createCheckKeeping(check, declared = listOf("aim"))
    single.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(
      10.0,
      single.labelBuffer("aim"),
      1e-9,
      "aim is still declared, so its history carries across the model change instead of restarting",
    )
    assertTrue("trigger" !in single.labelBufferSnapshot())
  }

  @Test
  fun `with no label set negotiated yet, restored buffers drain instead of being thrown away`() {
    val check = createCheck(declared = emptyList())
    check.restoreLabelBuffer("aim", 30.0)
    check.restoreLabelBuffer("trigger", 20.0)

    check.feedBuffers(attributed(mapOf(AiCheck.UNATTRIBUTED_LABEL to 0.95)))

    assertEquals(
      29.0,
      check.labelBuffer("aim"),
      1e-9,
      "an empty label set also means the server has not spoken yet, and a login restores " +
        "labels before it does, so dropping here would erase a returning cheater's history",
    )
    assertEquals(19.0, check.labelBuffer("trigger"), 1e-9)
    assertEquals(5.0, check.labelBuffer(AiCheck.UNATTRIBUTED_LABEL), 1e-9)
  }

  private fun createCheckKeeping(source: AiCheck, declared: List<String>): AiCheck {
    val fresh = createCheck(declared = declared)
    for ((label, value) in source.labelBufferSnapshot()) fresh.restoreLabelBuffer(label, value)
    return fresh
  }

  private fun attributed(values: Map<String, Double>) = LabelledVerdict(values, attributed = true)

  private fun createCheck(
    flag: Double = 1_000.0,
    declared: List<String> = listOf("aim", "trigger", "reach"),
  ): AiCheck {
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns logger

    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.aiFlag } returns flag
    every { configManager.aiResetOnFlag } returns 0.0
    every { configManager.aiBufferMultiplier } returns 100.0
    every { configManager.aiBufferDecrease } returns 1.0
    every { configManager.suspiciousAlertsBuffer } returns 25.0
    every { configManager.aiLabelMaxTracked } returns 32
    every { configManager.aiLabelSplit } returns true
    every { configManager.aiLabels } returns declared
    every { configManager.enabledDebugCategories } returns emptySet()

    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"
    every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")

    val user = mockk<User>(relaxed = true)
    every { user.clientVersion } returns ClientVersion.V_1_21_4

    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.uuid } returns player.uniqueId
    every { shardPlayer.eventBus } returns mockk<ShardEventBus>(relaxed = true)
    every { shardPlayer.punishmentManager } returns mockk<PunishmentManager>(relaxed = true)
    every { shardPlayer.combat } returns CombatState()
    every { shardPlayer.user } returns user

    return AiCheck(
      shardPlayer = shardPlayer,
      plugin = plugin,
      aiService = mockk<AiService>(relaxed = true),
      configManager = configManager,
      regionProvider = mockk<RegionProvider>(relaxed = true),
      alertManager = mockk<AlertManager>(relaxed = true),
      damageProcessor = mockk<DamageProcessor>(relaxed = true),
      debugManager = DebugManager(plugin, configManager),
      scheduler = mockk<SchedulerService>(relaxed = true),
      mitigationScorer = mockk<MitigationScorer>(relaxed = true),
    )
  }
}
