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

import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.ScoreDisplay
import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.player.PlayerDataManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.entity.Player

class TargetTeamStateTest {
  @Test
  fun `recreated below-name objective resends unchanged entry`() {
    val viewer = mockk<Player>(relaxed = true)
    val belowNameBridge = mockk<ScoreboardPacketBridge>(relaxed = true)
    every { belowNameBridge.supportsFancyText(viewer) } returns true

    val state = TargetTeamState("slv_test")
    val rendered = RenderedTag("", "", "<white>95%</white>", 95)

    state.updateBelowName(viewer, "svw_test", "Target", rendered, belowNameBridge)
    state.invalidateBelowName()
    state.updateBelowName(viewer, "svw_test", "Target", rendered, belowNameBridge)

    verify(exactly = 2) {
      belowNameBridge.updateEntry(
        viewer,
        "svw_test",
        "Target",
        95,
        ScoreDisplay(entryDisplay = null, scoreText = rendered.below),
      )
    }
  }

  @Test
  fun `below-name entry falls back to placeholder when target has no ai data`() {
    val viewer = mockk<Player>(relaxed = true)
    val belowNameBridge = mockk<ScoreboardPacketBridge>(relaxed = true)
    every { belowNameBridge.supportsFancyText(viewer) } returns true

    val state = TargetTeamState("slv_test")
    val withData = RenderedTag("", "", "<white>95%</white>", 95)
    val noData = RenderedTag("", "", "<gray>--</gray>", 0)

    state.updateBelowName(viewer, "svw_test", "Target", withData, belowNameBridge)
    state.updateBelowName(viewer, "svw_test", "Target", noData, belowNameBridge)

    verify(exactly = 1) {
      belowNameBridge.updateEntry(
        viewer,
        "svw_test",
        "Target",
        0,
        ScoreDisplay(entryDisplay = null, scoreText = noData.below),
      )
    }
  }

  @Test
  fun `renderer emits fallback below-name score without registered shard player`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<Player>(relaxed = true)
    val state = TargetTeamState("slv_test")
    val config =
      ViewRuntimeConfig(
        updateTicks = 2,
        rebindCycles = 10,
        resyncCycles = 50,
        pingRefreshCycles = 20,
        pingBucketMs = 10,
        placement = ViewPlacement.BELOW_NAME,
        belowTitle = "<white>AI</white>",
        fallbackProb = "--",
        fallbackBuffer = "--",
        probDecimals = 1,
        bufferDecimals = 1,
        prefixTemplate = "<white>{prob}</white>",
        suffixTemplate = "<white>{buffer}</white>",
        belowTemplate = "<white>{prob}</white>",
        defaultBelowText = "<white>--</white>",
        usesPing = false,
      )

    every { playerDataManager.getPlayer(target) } returns null

    val rendered = ViewTagRenderer(MonitorSampler(playerDataManager)).render(target, "", config)

    assertEquals("<white>--</white>", rendered.below)
    assertEquals(0, rendered.belowScore)
  }
}
