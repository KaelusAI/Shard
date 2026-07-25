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

import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.AiCheck
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

    val sample = MonitorSampler(playerDataManager).sample(target)

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

  @Test
  fun `known player without ai check is present but not active`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = target()
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns CombatState(0).also { it.damageMultiplier = 0.5 }
    every { checkManager.getCheck(AiCheck::class.java) } returns null

    val sample = MonitorSampler(playerDataManager).sample(target)

    assertTrue(sample.dataPresent)
    assertFalse(sample.aiActive)
    assertEquals(0.0, sample.probability)
    assertEquals(0.5, sample.damageMultiplier)
  }

  @Test
  fun `active ai check carries every value through`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = target()
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    val aiCheck = mockk<AiCheck>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns CombatState(0).also { it.damageMultiplier = 0.25 }
    every { checkManager.getCheck(AiCheck::class.java) } returns aiCheck
    every { aiCheck.lastProbability } returns 0.87
    every { aiCheck.buffer } returns 31.5
    every { aiCheck.prob90 } returns 4

    val sample = MonitorSampler(playerDataManager).sample(target)

    assertTrue(sample.dataPresent)
    assertTrue(sample.aiActive)
    assertEquals(0.87, sample.probability)
    assertEquals(31.5, sample.buffer)
    assertEquals(0.25, sample.damageMultiplier)
    assertEquals(4, sample.prob90)
  }
}
