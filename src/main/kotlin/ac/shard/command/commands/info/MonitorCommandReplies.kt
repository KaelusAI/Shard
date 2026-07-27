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

import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorTargetsService
import ac.shard.monitor.hud.TargetChange
import ac.shard.monitor.hud.outputCapacity
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import org.bukkit.entity.Player

internal fun restartIfSessionShapeChanged(
  hudService: MonitorHudService,
  player: Player,
  before: MonitorSettings,
  updated: MonitorSettings,
) {
  if (before.outputs != updated.outputs || before.chatStyle != updated.chatStyle) {
    hudService.restart(player.uniqueId)
  }
}

internal fun replyToAdd(
  viewer: Player,
  target: Player,
  change: TargetChange,
  targets: MonitorTargetsService,
  hudService: MonitorHudService,
) {
  when (change) {
    TargetChange.APPLIED -> replyAdded(viewer, target, targets, hudService)
    TargetChange.ALREADY_WATCHED ->
      MessageUtil.sendMessage(viewer, Message.MONITOR_TARGET_ALREADY, "player", target.name)
    else ->
      MessageUtil.sendMessage(
        viewer,
        Message.MONITOR_TARGET_LIMIT,
        "output",
        hudService.session(viewer.uniqueId)?.outputs?.joinToString(", ") { it.kind.key }.orEmpty(),
        "limit",
        targets.capacity(viewer.uniqueId).toString(),
      )
  }
}

private fun replyAdded(
  viewer: Player,
  target: Player,
  targets: MonitorTargetsService,
  hudService: MonitorHudService,
) {
  val total = targets.size(viewer.uniqueId)
  MessageUtil.sendMessage(
    viewer,
    Message.MONITOR_TARGET_ADDED,
    "player",
    target.name,
    "count",
    total.toString(),
  )
  val session = hudService.session(viewer.uniqueId) ?: return
  val narrowest = session.outputs.minByOrNull { outputCapacity(it, session.config) } ?: return
  val shown = outputCapacity(narrowest, session.config)
  if (total > shown) {
    MessageUtil.sendMessage(
      viewer,
      Message.MONITOR_TARGET_OVERFLOW,
      "output",
      narrowest.kind.key,
      "shown",
      shown.toString(),
    )
  }
}
