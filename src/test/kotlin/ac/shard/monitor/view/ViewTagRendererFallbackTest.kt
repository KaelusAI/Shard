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
package ac.shard.monitor.view

import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.monitor.core.MonitorSampler
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewTagRendererFallbackTest {
  private val config =
    ViewRuntimeConfig(
      updateTicks = 2,
      rebindCycles = 10,
      resyncCycles = 50,
      pingRefreshCycles = 20,
      pingBucketMs = 10,
      placement = ViewPlacement.BELOW_NAME,
      belowTitle = "",
      fallbackProb = "--",
      fallbackBuffer = "??",
      probDecimals = 0,
      bufferDecimals = 2,
      prefixTemplate = "{prob}|{buffer}",
      suffixTemplate = "",
      belowTemplate = "{prob}",
      defaultBelowText = "--",
      usesPing = false,
    )

  @Test
  fun `unknown player falls back on both values and scores zero`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    every { playerDataManager.getPlayer(target) } returns null

    val rendered = ViewTagRenderer(MonitorSampler(playerDataManager)).render(target, "", config)

    assertEquals("--|??", rendered.prefix)
    assertEquals("--", rendered.below)
    assertEquals(0, rendered.belowScore)
  }

  @Test
  fun `known player without ai check falls back on both values and scores zero`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns mockk(relaxed = true)
    every { checkManager.getCheck(AiCheck::class.java) } returns null

    val rendered = ViewTagRenderer(MonitorSampler(playerDataManager)).render(target, "", config)

    assertEquals("--|??", rendered.prefix)
    assertEquals("--", rendered.below)
    assertEquals(0, rendered.belowScore)
  }

  @Test
  fun `present ai check renders formatted values and a rounded score`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    val aiCheck = mockk<AiCheck>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns mockk(relaxed = true)
    every { checkManager.getCheck(AiCheck::class.java) } returns aiCheck
    every { aiCheck.lastProbability } returns 0.954
    every { aiCheck.buffer } returns 12.5
    every { aiCheck.prob90 } returns 0

    val rendered = ViewTagRenderer(MonitorSampler(playerDataManager)).render(target, "", config)

    assertEquals("95|12.50", rendered.prefix)
    assertEquals("95", rendered.below)
    assertEquals(95, rendered.belowScore)
  }
}
