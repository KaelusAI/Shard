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
package ac.shard.monitor.hud.output

import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.monitor.hud.MonitorFrame
import ac.shard.monitor.hud.MonitorRenderPayload
import ac.shard.monitor.hud.SIDEBAR_MAX_LINES
import ac.shard.monitor.hud.SidebarConfig
import ac.shard.monitor.hud.fillFrameTemplate
import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot

internal fun sidebarObjectiveName(viewerId: UUID, sessionId: Long): String {
  val viewerPart = viewerId.toString().replace("-", "").substring(0, SIDEBAR_HASH_LENGTH)
  val sessionPart =
    (sessionId and SIDEBAR_SESSION_MASK)
      .toString(SIDEBAR_SESSION_RADIX)
      .padStart(SIDEBAR_SESSION_LENGTH, '0')
  return SIDEBAR_OBJECTIVE_PREFIX + viewerPart + sessionPart
}

internal fun sidebarEntryKey(index: Int): String = "§" + ENTRY_KEY_ALPHABET[index]

internal fun buildSidebarLines(payload: MonitorRenderPayload, config: SidebarConfig): List<String> =
  payload.frames
    .map { frame -> frameLines(frame, config) }
    .reduceOrNull { drawn, next -> drawn + config.targetSeparator + next }
    .orEmpty()
    .take(SIDEBAR_MAX_LINES)

internal fun restoreSidebar(
  bridge: ScoreboardPacketBridge,
  viewer: Player,
  slot: Int,
  observed: String?,
) {
  val target = observed ?: bukkitObjectiveName(viewer, slot).orEmpty()
  bridge.displayObjective(viewer, target, slot)
}

private fun frameLines(frame: MonitorFrame, config: SidebarConfig): List<String> {
  if (!frame.dataPresent || !frame.aiActive) {
    return listOf(fillFrameTemplate(config.unavailableLine, frame))
  }
  val lines = config.lines.map { fillFrameTemplate(it, frame) }
  return if (config.dropBlankLines) lines.filter { it.isNotBlank() } else lines
}

private fun bukkitObjectiveName(viewer: Player, slot: Int): String? {
  val displaySlot =
    when (slot) {
      TAB_LIST_SLOT -> DisplaySlot.PLAYER_LIST
      SIDEBAR_SLOT -> DisplaySlot.SIDEBAR
      BELOW_NAME_SLOT -> DisplaySlot.BELOW_NAME
      else -> null
    }
  return displaySlot?.let { viewer.scoreboard.getObjective(it)?.name }
}

internal const val SIDEBAR_MAX_TARGETS = 4
private const val SIDEBAR_OBJECTIVE_PREFIX = "shm_"
private const val SIDEBAR_HASH_LENGTH = 8
private const val SIDEBAR_SESSION_LENGTH = 4
private const val SIDEBAR_SESSION_MASK = 0xFFFFL
private const val SIDEBAR_SESSION_RADIX = 16
private const val ENTRY_KEY_ALPHABET = "0123456789abcdef"
private const val TAB_LIST_SLOT = 0
private const val SIDEBAR_SLOT = 1
private const val BELOW_NAME_SLOT = 2
