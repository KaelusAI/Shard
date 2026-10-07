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

import ac.shard.ai.stream.BlockReason
import ac.shard.ai.stream.ModelSpec
import ac.shard.data.CollectManager
import ac.shard.detection.DetectionState
import ac.shard.detection.PlayerInference
import ac.shard.mitigation.EffectChannel
import ac.shard.mitigation.MitigationState
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.player.state.CombatState
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player

class MonitorSamplerTest {
  private val targetId = UUID.fromString("11111111-2222-3333-4444-555555555555")

  private fun target(): Player {
    val target = mockk<Player>(relaxed = true)
    every { target.uniqueId } returns targetId
    every { target.name } returns "Target"
    every { target.ping } returns 42
    return target
  }

  @Test
  fun `unknown player is neither present nor active`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = target()
    every { playerDataManager.getPlayer(target) } returns null

    val sample = MonitorSampler(playerDataManager, noCollector()).sample(target)

    assertFalse(sample.dataPresent)
    assertFalse(sample.aiActive)
    assertEquals(0.0, sample.probability)
    assertEquals(0.0, sample.buffer)
    assertEquals(1.0, sample.damageMultiplier)
    assertEquals(0, sample.prob90)
    assertEquals(42, sample.rawPing)
    assertEquals(targetId, sample.targetId)
    assertEquals("Target", sample.targetName)
  }

  private class Active(
    val players: PlayerDataManager,
    val target: Player,
    val aiCheck: PlayerInference,
  ) {
    fun inference(): MonitorInferenceInfo? =
      MonitorSampler(players, noCollector()).sample(target).inference

    fun status(): String? = inference()?.status
  }

  private fun active(): Active {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = target()
    val shardPlayer = mockk<ShardPlayer>()
    val aiCheck = mockk<PlayerInference>()
    val state = mockk<DetectionState>()
    val mitigation = MitigationState()
    mitigation.updatePhase { it.copy(activeEffects = mapOf(EffectChannel.MELEE to 0.25)) }
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.combat } returns CombatState(0)
    every { shardPlayer.mitigation } returns mitigation
    every { shardPlayer.ai } returns aiCheck
    every { shardPlayer.detection } returns state
    every { state.lastProbability } returns 0.87
    every { state.primaryBuffer } returns 31.5
    every { state.prob90 } returns 4
    every { aiCheck.inferenceProgressByModel() } returns emptyList()
    every { aiCheck.streamBlockReason } returns null
    every { state.shownModelTitle } returns ""
    every { state.modelCards() } returns emptyList()
    every { state.labelBufferSnapshot() } returns emptyMap()
    every { state.lastCheatProbability } returns 0.87
    every { state.lastLabelProbabilities } returns emptyMap()
    every { state.declaredLabels } returns emptyList()
    return Active(playerDataManager, target, aiCheck)
  }

  @Test
  fun `active ai check carries every value through`() {
    val f = active()

    val sample = MonitorSampler(f.players, noCollector()).sample(f.target)

    assertTrue(sample.dataPresent)
    assertTrue(sample.aiActive)
    assertEquals(0.87, sample.probability)
    assertEquals(31.5, sample.buffer)
    assertEquals(0.25, sample.damageMultiplier)
    assertEquals(4, sample.prob90)
    assertEquals(MonitorInferenceInfo("idle", fault = false), sample.inference)

    every { f.aiCheck.streamBlockReason } returns BlockReason.COOLDOWN
    assertEquals(
      MonitorInferenceInfo("paused", fault = true),
      MonitorSampler(f.players, noCollector()).sample(f.target).inference,
    )

    every { f.aiCheck.streamBlockReason } returns BlockReason.VEHICLE
    assertEquals(
      MonitorInferenceInfo("vehicle", fault = false),
      MonitorSampler(f.players, noCollector()).sample(f.target).inference,
    )
  }

  @Test
  fun `several models are each named unless they share one state`() {
    val f = active()
    val main = mockk<ModelSpec> { every { displayTitle } returns "A" }
    val side = mockk<ModelSpec> { every { displayTitle } returns "B" }

    every { f.aiCheck.inferenceProgressByModel() } returns
      listOf(main to intArrayOf(12, 32), side to intArrayOf(3, 8))
    assertEquals("A 12/32, B 3/8", f.status(), "each model counts its own window")
    assertTrue(f.inference()!!.named, "named models drop the plain prefix")

    every { f.aiCheck.inferenceProgressByModel() } returns
      listOf(main to null, side to intArrayOf(3, 8))
    assertEquals("A idle, B 3/8", f.status())

    every { f.aiCheck.inferenceProgressByModel() } returns listOf(main to null, side to null)
    assertEquals("idle", f.status(), "models in the same state read as one")
    assertFalse(f.inference()!!.named)
  }
}

private fun noCollector(): CollectManager =
  mockk<CollectManager>(relaxed = true).also { every { it.getSession(any()) } returns null }
