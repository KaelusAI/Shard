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
package ac.shard.monitor.hud

import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorChatStyle
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.bukkit.entity.Player
import org.spongepowered.configurate.CommentedConfigurationNode

class OutputStatesTest {
  private val viewerId = UUID.randomUUID()
  private val runtimeConfig =
    MonitorHudRuntimeConfig.from(
      ConfigView(CommentedConfigurationNode.root()),
      2,
      Logger.getLogger("output-states"),
    )

  private fun context(sessionId: Long): MonitorRenderContext {
    val viewer = mockk<Player>(relaxed = true)
    every { viewer.uniqueId } returns viewerId
    return MonitorRenderContext(
      viewer,
      viewerId,
      sessionId,
      MonitorChatStyle.SUMMARY,
      runtimeConfig,
    )
  }

  @Test
  fun `state is readable within the session that stored it`() {
    val states = OutputStates<String>()
    val first = context(1L)
    states.put(first, "held")

    assertEquals("held", states.get(first))
  }

  @Test
  fun `a newer session cannot read the previous session's state`() {
    val states = OutputStates<String>()
    states.put(context(1L), "old")

    assertNull(states.get(context(2L)))
  }

  @Test
  fun `a late teardown cannot delete a newer session's state`() {
    val states = OutputStates<String>()
    val stale = context(1L)
    val fresh = context(2L)
    states.put(stale, "old")
    states.put(fresh, "new")

    assertNull(states.remove(stale))
    assertEquals("new", states.get(fresh))
  }

  @Test
  fun `removing the owning session hands back the stored state`() {
    val states = OutputStates<String>()
    val session = context(7L)
    states.put(session, "held")

    assertEquals("held", states.remove(session))
    assertNull(states.get(session))
  }
}
