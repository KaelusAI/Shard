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

import ac.shard.config.LocaleManager
import java.util.Locale
import java.util.UUID
import org.bukkit.entity.Player

enum class TargetChange {
  APPLIED,
  ALREADY_WATCHED,
  NOT_WATCHED,
  LIMIT_REACHED,
  NO_SESSION,
}

class MonitorTargetsService(
  private val hudService: MonitorHudService,
  private val index: MonitorTargetIndex,
  private val localeManager: LocaleManager,
) {
  fun add(viewer: Player, target: Player): TargetChange {
    val session = hudService.session(viewer.uniqueId) ?: return TargetChange.NO_SESSION
    return when {
      session.targets.state(target.uniqueId) != null -> TargetChange.ALREADY_WATCHED
      session.targets.size >= capacityFor(session) -> TargetChange.LIMIT_REACHED
      else -> {
        session.trackTarget(target, targetTexts(localeManager, target.name))
        index.set(viewer.uniqueId, session.targets.ids())
        TargetChange.APPLIED
      }
    }
  }

  fun remove(viewer: Player, targetName: String): TargetChange {
    val session = hudService.session(viewer.uniqueId)
    val targetId = session?.let { idByName(it, targetName) }
    if (session == null || targetId == null) {
      return if (session == null) TargetChange.NO_SESSION else TargetChange.NOT_WATCHED
    }
    session.targets.remove(targetId)
    if (session.targets.size == 0) {
      hudService.stop(viewer.uniqueId, viewer)
    } else {
      index.set(viewer.uniqueId, session.targets.ids())
    }
    return TargetChange.APPLIED
  }

  fun clear(viewer: Player): Int {
    val watched = hudService.session(viewer.uniqueId)?.targets?.size ?: 0
    hudService.stop(viewer.uniqueId, viewer)
    return watched
  }

  fun names(viewerId: UUID): List<String> = hudService.session(viewerId)?.targets?.names().orEmpty()

  fun size(viewerId: UUID): Int = hudService.session(viewerId)?.targets?.size ?: 0

  fun capacity(viewerId: UUID): Int = hudService.session(viewerId)?.let { capacityFor(it) } ?: 1

  private fun capacityFor(session: MonitorHudSession): Int =
    effectiveCapacity(session.outputs, session.config)

  private fun idByName(session: MonitorHudSession, targetName: String): UUID? =
    session.targets
      .all()
      .firstOrNull { it.targetName.equals(targetName, ignoreCase = true) }
      ?.targetId
}

internal fun normalizeTargetName(raw: String): String = raw.trim().lowercase(Locale.ROOT)
