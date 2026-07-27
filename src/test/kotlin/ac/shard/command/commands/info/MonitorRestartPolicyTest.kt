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
package ac.shard.command.commands.info

import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import ac.shard.monitor.hud.MonitorHudService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import org.bukkit.entity.Player

class MonitorRestartPolicyTest {
  private val hudService = mockk<MonitorHudService>(relaxed = true)
  private val viewerId = UUID.randomUUID()

  private val player = mockk<Player>(relaxed = true).also { every { it.uniqueId } returns viewerId }

  private fun settings(
    outputs: Set<MonitorOutputKind> = setOf(MonitorOutputKind.ACTIONBAR),
    chatStyle: MonitorChatStyle = MonitorChatStyle.SUMMARY,
    theme: MonitorTheme = MonitorTheme.CALM,
    mode: MonitorMode = MonitorMode.COMPACT,
    showPing: Boolean = true,
  ) =
    MonitorSettings(
      mode = mode,
      theme = theme,
      showPing = showPing,
      showDmg = true,
      showTrend = true,
      showName = MonitorNameMode.AUTO,
      outputs = outputs,
      chatStyle = chatStyle,
    )

  @Test
  fun `changing the output restarts the session`() {
    restartIfSessionShapeChanged(
      hudService,
      player,
      settings(),
      settings(outputs = setOf(MonitorOutputKind.SIDEBAR)),
    )

    verify(exactly = 1) { hudService.restart(viewerId) }
  }

  @Test
  fun `changing the chat style restarts the session`() {
    restartIfSessionShapeChanged(
      hudService,
      player,
      settings(),
      settings(chatStyle = MonitorChatStyle.LIVE),
    )

    verify(exactly = 1) { hudService.restart(viewerId) }
  }

  @Test
  fun `changing the theme redraws on the next tick instead of restarting`() {
    restartIfSessionShapeChanged(
      hudService,
      player,
      settings(),
      settings(theme = MonitorTheme.VIVID),
    )

    verify(exactly = 0) { hudService.restart(any()) }
  }

  @Test
  fun `changing the mode does not restart`() {
    restartIfSessionShapeChanged(hudService, player, settings(), settings(mode = MonitorMode.FULL))

    verify(exactly = 0) { hudService.restart(any()) }
  }

  @Test
  fun `toggling a token does not restart`() {
    restartIfSessionShapeChanged(hudService, player, settings(), settings(showPing = false))

    verify(exactly = 0) { hudService.restart(any()) }
  }
}
