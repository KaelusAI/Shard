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

import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorOutputRegistry
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import org.bukkit.entity.Player

enum class OutputChange {
  SET,
  ADD,
  REMOVE,
}

class MonitorOutputSelector(
  private val registry: MonitorOutputRegistry,
  private val hudService: MonitorHudService,
) {
  fun resolve(
    player: Player,
    raw: String,
    mode: OutputChange,
    current: Set<MonitorOutputKind>,
  ): Set<MonitorOutputKind>? {
    val requested = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val kinds = requested.map { name ->
      MonitorOutputKind.entries.firstOrNull { it.key.equals(name, true) }
    }
    if (kinds.isEmpty() || kinds.any { it == null }) {
      MessageUtil.sendMessage(
        player,
        Message.MONITOR_OUTPUT_INVALID,
        "options",
        MonitorOutputKind.entries.joinToString("/") { it.key },
      )
      return null
    }
    val picked = kinds.filterNotNull()
    return if (mode == OutputChange.REMOVE) {
      drop(player, current, picked)
    } else {
      merge(player, current, picked, mode)
    }
  }

  private fun merge(
    player: Player,
    current: Set<MonitorOutputKind>,
    picked: List<MonitorOutputKind>,
    mode: OutputChange,
  ): Set<MonitorOutputKind>? {
    val rejected = picked.firstOrNull { !allowed(player, it) }
    if (rejected != null) {
      return null
    }
    val base = if (mode == OutputChange.ADD) current else emptySet()
    return LinkedHashSet(base + picked)
  }

  private fun drop(
    player: Player,
    current: Set<MonitorOutputKind>,
    picked: List<MonitorOutputKind>,
  ): Set<MonitorOutputKind>? {
    val left = current - picked.toSet()
    if (left.isEmpty()) {
      MessageUtil.sendMessage(
        player,
        Message.MONITOR_OUTPUT_LAST,
        "output",
        picked.joinToString(", ") { it.key },
      )
      return null
    }
    return left
  }

  private fun allowed(player: Player, kind: MonitorOutputKind): Boolean {
    val fallback = MonitorOutputKind.ACTIONBAR.key
    val message =
      when {
        !player.hasPermission(kind.permission) -> Message.MONITOR_OUTPUT_NO_PERMISSION
        !hudService.runtimeConfig.isEnabled(kind) -> Message.MONITOR_OUTPUT_DISABLED
        !registry.isSupported(kind) -> Message.MONITOR_OUTPUT_UNSUPPORTED
        else -> null
      }
    message?.let { MessageUtil.sendMessage(player, it, "output", kind.key, "fallback", fallback) }
    return message == null
  }
}
