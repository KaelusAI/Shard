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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MonitorTargetIndex {
  private val viewersByTarget = ConcurrentHashMap<UUID, MutableSet<UUID>>()
  private val targetsByViewer = ConcurrentHashMap<UUID, Set<UUID>>()

  fun set(viewerId: UUID, targets: Collection<UUID>) {
    clear(viewerId)
    targetsByViewer[viewerId] = targets.toSet()
    targets.forEach { viewersByTarget.computeIfAbsent(it) { _ -> newViewerSet() }.add(viewerId) }
  }

  fun clear(viewerId: UUID) {
    val previous = targetsByViewer.remove(viewerId) ?: return
    previous.forEach { targetId ->
      viewersByTarget.computeIfPresent(targetId) { _, viewers ->
        viewers.remove(viewerId)
        viewers.ifEmpty { null }
      }
    }
  }

  fun viewersOf(targetId: UUID): Set<UUID> = viewersByTarget[targetId].orEmpty().toSet()

  private fun newViewerSet(): MutableSet<UUID> = ConcurrentHashMap.newKeySet()
}
