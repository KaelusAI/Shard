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

import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import org.bukkit.entity.Player

class TargetTeamRebindTest {
  private val rendered = RenderedTag("p", "s", "b", 10)

  private fun created(viewer: Player, bridge: ViewTeamPacketBridge): TargetTeamState {
    val state = TargetTeamState("slv_test")
    state.updateTeam(viewer, rebindCycles = 100, targetName = "Target", rendered, bridge)
    return state
  }

  @Test
  fun `a pending rebind fires once and then clears`() {
    val viewer = mockk<Player>(relaxed = true)
    val bridge = mockk<ViewTeamPacketBridge>(relaxed = true)
    val state = created(viewer, bridge)

    state.markRebindNeeded()
    state.updateTeam(viewer, 100, "Target", rendered, bridge)
    state.updateTeam(viewer, 100, "Target", rendered, bridge)

    verify(exactly = 1) { bridge.rebindEntity(viewer, "slv_test", "Target") }
  }

  @Test
  fun `no rebind happens while nothing asks for one`() {
    val viewer = mockk<Player>(relaxed = true)
    val bridge = mockk<ViewTeamPacketBridge>(relaxed = true)
    val state = created(viewer, bridge)

    state.updateTeam(viewer, 100, "Target", rendered, bridge)
    state.updateTeam(viewer, 100, "Target", rendered, bridge)

    verify(exactly = 0) { bridge.rebindEntity(any(), any(), any()) }
  }

  @Test
  fun `the cycle counter still forces a periodic rebind`() {
    val viewer = mockk<Player>(relaxed = true)
    val bridge = mockk<ViewTeamPacketBridge>(relaxed = true)
    val state = created(viewer, bridge)

    repeat(3) {
      state.updateTeam(viewer, rebindCycles = 2, targetName = "Target", rendered, bridge)
    }

    verify(atLeast = 1) { bridge.rebindEntity(viewer, "slv_test", "Target") }
  }
}
