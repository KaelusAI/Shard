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

import ac.shard.monitor.core.ScoreboardPacketBridge
import java.util.HashSet
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player

internal class ViewTargetTracker(
  private val tagRenderer: ViewTagRenderer,
  private val teamBridge: ViewTeamPacketBridge,
  private val bridge: ScoreboardPacketBridge,
) {
  fun trackTarget(session: ViewSession, target: Player) {
    session.updateTrackedName(target.uniqueId, target.name)
    session.updateTrackedEntityId(target.uniqueId, target.entityId)
    session.targetTeams.computeIfAbsent(target.uniqueId) { TargetTeamState(teamNameForView(it)) }
  }

  fun refreshTrackedTargets(viewer: Player, session: ViewSession) {
    for ((targetUuid, state) in session.targetTeams.entries.toList()) {
      val target = Bukkit.getPlayer(targetUuid)
      if (target == null || !shouldTrackTarget(viewer, target)) {
        removeTrackedTarget(viewer, session, targetUuid, target?.name)
        continue
      }
      updateTarget(viewer, session, target, state)
    }
  }

  fun bootstrapTrackedTargets(viewer: Player, session: ViewSession) {
    for (target in Bukkit.getOnlinePlayers()) {
      if (!shouldTrackTarget(viewer, target)) {
        continue
      }
      trackTarget(session, target)
    }
  }

  fun resyncTrackedTargets(viewer: Player, session: ViewSession) {
    val seenTargets = HashSet<UUID>()
    for (target in Bukkit.getOnlinePlayers()) {
      if (!shouldTrackTarget(viewer, target)) {
        continue
      }
      seenTargets.add(target.uniqueId)
      trackTarget(session, target)
    }

    for (targetId in session.targetTeams.keys.toList()) {
      if (seenTargets.contains(targetId)) {
        continue
      }
      removeTrackedTarget(viewer, session, targetId, session.targetNameFor(targetId))
    }
  }

  fun removeTrackedTarget(
    viewer: Player,
    session: ViewSession,
    targetId: UUID,
    fallbackTargetName: String?,
  ) {
    val state = session.targetTeams.remove(targetId)
    session.removeTrackedEntityId(targetId)
    session.removeTrackedName(targetId, fallbackTargetName)
    if (state == null) {
      return
    }
    state.removeFromViewer(
      viewer,
      if (session.usesBelowName()) session.belowObjectiveName else null,
      fallbackTargetName.orEmpty(),
      bridge,
      teamBridge,
    )
  }

  fun dropTrackedTarget(session: ViewSession, targetId: UUID, fallbackTargetName: String?) {
    session.targetTeams.remove(targetId)
    session.removeTrackedEntityId(targetId)
    session.removeTrackedName(targetId, fallbackTargetName)
  }

  fun clearTrackedTargets(viewer: Player, session: ViewSession) {
    for ((targetId, state) in session.targetTeams.entries) {
      state.removeFromViewer(
        viewer,
        if (session.usesBelowName()) session.belowObjectiveName else null,
        session.targetNameFor(targetId).orEmpty(),
        bridge,
        teamBridge,
      )
    }
    session.clearTargets()
  }

  private fun updateTarget(
    viewer: Player,
    session: ViewSession,
    target: Player,
    state: TargetTeamState,
  ) {
    session.updateTrackedEntityId(target.uniqueId, target.entityId)
    val pingDisplay = state.resolvePingDisplay(target.ping, session.config)
    val rendered = tagRenderer.render(target, pingDisplay, session.config)

    if (session.usesBelowName()) {
      state.updateBelowName(viewer, session.belowObjectiveName, target.name, rendered, bridge)
    } else {
      state.updateTeam(viewer, session.config.rebindCycles, target.name, rendered, teamBridge)
    }

    session.updateTrackedName(target.uniqueId, state.lastTargetName.ifBlank { target.name })
  }

  private fun shouldTrackTarget(viewer: Player, target: Player): Boolean {
    return target.isOnline && viewer.world.uid == target.world.uid && viewer.canSee(target)
  }
}
