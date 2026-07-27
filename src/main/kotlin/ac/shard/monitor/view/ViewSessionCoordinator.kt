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

import ac.shard.Shard
import ac.shard.monitor.core.ComponentCache
import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.monitor.core.ScoreboardSlotRegistry
import ac.shard.monitor.core.SlotClaim
import ac.shard.scheduler.SchedulerService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.entity.Player

internal class ViewSessionCoordinator(
  private val plugin: Shard,
  private val scheduler: SchedulerService,
  componentCache: ComponentCache,
  sampler: MonitorSampler,
  private val slotRegistry: ScoreboardSlotRegistry,
  private val bridge: ScoreboardPacketBridge,
) {
  private val sessions = ConcurrentHashMap<UUID, ViewSession>()
  private val teamBridge = ViewTeamPacketBridge(componentCache)
  private val tracker = ViewTargetTracker(ViewTagRenderer(sampler), teamBridge, bridge)
  private val slotGuard = ViewSlotGuard(plugin, tracker, bridge) { viewerId -> sessions[viewerId] }

  fun session(viewerId: UUID): ViewSession? = sessions[viewerId]

  val activeViewerIds: List<UUID>
    get() = sessions.keys.toList()

  fun enable(viewer: Player, config: ViewRuntimeConfig) {
    val viewerId = viewer.uniqueId
    if (sessions.containsKey(viewerId)) {
      return
    }

    val objectiveName =
      if (config.placement == ViewPlacement.BELOW_NAME) {
        objectiveNameForViewer(viewerId)
      } else {
        null
      }

    val session = ViewSession(config, config.placement, objectiveName)
    sessions[viewerId] = session

    if (session.usesBelowName()) {
      bridge.createObjective(viewer, config.objectiveSpec(objectiveName!!))
      slotRegistry.claim(viewerId, SlotClaim(config.slot, objectiveName, slotGuard))
    }

    tracker.bootstrapTrackedTargets(viewer, session)
    tracker.refreshTrackedTargets(viewer, session)
    session.task =
      scheduler.runTimer(
        viewer,
        Runnable { refreshViewer(viewerId, ::resolveViewSessionViewer) },
        1L,
        config.updateTicks,
      )
  }

  fun reload(config: ViewRuntimeConfig) {
    for (viewerId in activeViewerIds) {
      val viewer = Bukkit.getPlayer(viewerId)
      disable(viewerId, viewer)
      if (viewer != null && viewer.isOnline && viewer.hasPermission(VIEW_PERMISSION)) {
        enable(viewer, config)
      }
    }
  }

  fun refreshViewer(viewerId: UUID, viewerResolver: (UUID) -> Player?) {
    val session = sessions[viewerId] ?: return
    val viewer = viewerResolver(viewerId) ?: return

    if (session.shouldResync()) {
      tracker.resyncTrackedTargets(viewer, session)
    }
    tracker.refreshTrackedTargets(viewer, session)
  }

  fun trackTarget(viewerId: UUID, target: Player) {
    val session = sessions[viewerId] ?: return
    tracker.trackTarget(session, target)
  }

  fun removeTrackedTarget(viewerId: UUID, targetId: UUID, fallbackTargetName: String?) {
    val session = sessions[viewerId] ?: return
    val viewer = resolveViewSessionViewer(viewerId) ?: return
    tracker.removeTrackedTarget(viewer, session, targetId, fallbackTargetName)
  }

  fun removeTrackedEntities(viewerId: UUID, entityIds: IntArray) {
    val session = sessions[viewerId] ?: return
    val viewer = resolveViewSessionViewer(viewerId) ?: return
    for (entityId in entityIds) {
      val targetId = session.targetIdByEntityId(entityId) ?: continue
      tracker.removeTrackedTarget(viewer, session, targetId, session.targetNameFor(targetId))
    }
  }

  fun removeTargetFromAllSessions(targetId: UUID, targetName: String) {
    for ((viewerId, session) in sessions.entries) {
      val viewer = Bukkit.getPlayer(viewerId)
      if (viewer != null && viewer.isOnline) {
        tracker.removeTrackedTarget(viewer, session, targetId, targetName)
      } else {
        tracker.dropTrackedTarget(session, targetId, targetName)
      }
    }
  }

  fun disable(viewerId: UUID, viewerHint: Player?) {
    val session = sessions.remove(viewerId) ?: return
    session.task?.cancel()
    slotRegistry.release(viewerId, session.config.slot)

    val viewer = viewerHint ?: Bukkit.getPlayer(viewerId)
    if (viewer != null && viewer.isOnline) {
      if (session.usesBelowName()) {
        session.belowObjectiveName?.let { bridge.removeObjective(viewer, it) }
      } else {
        tracker.clearTrackedTargets(viewer, session)
      }
    }

    session.clearTargets()
  }
}

private fun resolveViewSessionViewer(viewerId: UUID): Player? = Bukkit.getPlayer(viewerId)
