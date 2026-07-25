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

import ac.shard.monitor.core.PingSampler
import ac.shard.monitor.core.ScoreDisplay
import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.platform.scheduler.TaskHandle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.entity.Player

internal class ViewSession(
  val config: ViewRuntimeConfig,
  var placement: ViewPlacement,
  var belowObjectiveName: String?,
) {
  val targetTeams = ConcurrentHashMap<UUID, TargetTeamState>()
  private val targetNamesById = ConcurrentHashMap<UUID, String>()
  private val targetIdsByName = ConcurrentHashMap<String, UUID>()
  private val targetIdsByEntityId = ConcurrentHashMap<Int, UUID>()
  private val entityIdsByTargetId = ConcurrentHashMap<UUID, Int>()

  var task: TaskHandle? = null
  var cyclesSinceResync: Int = 0
  var belowNameConflictLogged: Boolean = false

  fun usesBelowName(): Boolean = placement == ViewPlacement.BELOW_NAME && belowObjectiveName != null

  fun shouldResync(): Boolean {
    cyclesSinceResync++
    if (cyclesSinceResync < config.resyncCycles) {
      return false
    }
    cyclesSinceResync = 0
    return true
  }

  fun updateTrackedName(targetId: UUID, targetName: String) {
    if (targetName.isBlank()) {
      return
    }

    val previousName = targetNamesById.put(targetId, targetName)
    if (previousName != null && previousName != targetName) {
      targetIdsByName.remove(previousName, targetId)
    }
    targetIdsByName[targetName] = targetId
  }

  fun removeTrackedName(targetId: UUID, fallbackTargetName: String?) {
    val trackedName = targetNamesById.remove(targetId) ?: fallbackTargetName
    if (trackedName != null) {
      targetIdsByName.remove(trackedName, targetId)
    }
  }

  fun targetIdByName(name: String): UUID? = targetIdsByName[name]

  fun targetNameFor(targetId: UUID): String? = targetNamesById[targetId]

  fun updateTrackedEntityId(targetId: UUID, entityId: Int) {
    val previousEntityId = entityIdsByTargetId.put(targetId, entityId)
    if (previousEntityId != null && previousEntityId != entityId) {
      targetIdsByEntityId.remove(previousEntityId, targetId)
    }
    targetIdsByEntityId[entityId] = targetId
  }

  fun removeTrackedEntityId(targetId: UUID) {
    val entityId = entityIdsByTargetId.remove(targetId) ?: return
    targetIdsByEntityId.remove(entityId, targetId)
  }

  fun targetIdByEntityId(entityId: Int): UUID? = targetIdsByEntityId[entityId]

  fun clearTargets() {
    targetTeams.clear()
    targetNamesById.clear()
    targetIdsByName.clear()
    targetIdsByEntityId.clear()
    entityIdsByTargetId.clear()
  }
}

internal class TargetTeamState(val teamName: String) {
  var created: Boolean = false
  var lastPrefix: String = ""
  var lastSuffix: String = ""
  var lastBelow: String = ""
  var lastBelowScore: Int = 0
  var lastTargetName: String = ""

  private var cyclesSinceRebind: Int = 0
  private val pendingRebind = AtomicBoolean(false)
  private val pingSampler = PingSampler()

  fun markRebindNeeded() {
    pendingRebind.set(true)
  }

  fun invalidateBelowName() {
    created = false
    lastBelow = ""
    lastBelowScore = 0
  }

  fun resolvePingDisplay(ping: Int, config: ViewRuntimeConfig): String {
    if (!config.usesPing) {
      return ""
    }
    return pingSampler.sample(ping, config.pingRefreshCycles, config.pingBucketMs)
  }

  fun updateBelowName(
    viewer: Player,
    objectiveName: String?,
    targetName: String,
    rendered: RenderedTag,
    bridge: ScoreboardPacketBridge,
  ) {
    val objective = objectiveName ?: return
    val belowScore = rendered.belowScore
    if (belowScore == null) {
      clearBelowName(viewer, objective, targetName, bridge)
      return
    }

    val nameChanged = lastTargetName.isNotBlank() && lastTargetName != targetName
    if (shouldUpdateBelowName(targetName, rendered, belowScore, bridge.supportsFancyText(viewer))) {
      if (nameChanged) {
        bridge.removeEntry(viewer, objective, lastTargetName)
      }
      bridge.updateEntry(
        viewer,
        objective,
        targetName,
        belowScore,
        ScoreDisplay(entryDisplay = null, scoreText = rendered.below),
      )
      lastBelow = rendered.below
      lastBelowScore = belowScore
      lastTargetName = targetName
      created = true
    }
  }

  private fun clearBelowName(
    viewer: Player,
    objective: String,
    targetName: String,
    bridge: ScoreboardPacketBridge,
  ) {
    if (created) {
      val entryName = lastTargetName.ifBlank { targetName }
      if (entryName.isNotBlank()) {
        bridge.removeEntry(viewer, objective, entryName)
      }
    }
    lastBelow = ""
    lastBelowScore = 0
    lastTargetName = targetName
    created = false
  }

  private fun shouldUpdateBelowName(
    targetName: String,
    rendered: RenderedTag,
    belowScore: Int,
    fancyTextSupported: Boolean,
  ): Boolean {
    val displayChanged = fancyTextSupported && rendered.below != lastBelow
    val scoreChanged = belowScore != lastBelowScore
    val nameChanged = lastTargetName.isNotBlank() && lastTargetName != targetName
    return !created || displayChanged || scoreChanged || nameChanged
  }

  fun updateTeam(
    viewer: Player,
    rebindCycles: Int,
    targetName: String,
    rendered: RenderedTag,
    teamBridge: ViewTeamPacketBridge,
  ) {
    val nameChanged = created && lastTargetName.isNotBlank() && lastTargetName != targetName
    if (!created) {
      teamBridge.createTeam(viewer, teamName, targetName, rendered)
      apply(rendered)
      created = true
      cyclesSinceRebind = 0
      pendingRebind.set(false)
      lastTargetName = targetName
      return
    }

    if (nameChanged) {
      teamBridge.removeTeam(viewer, teamName)
      teamBridge.createTeam(viewer, teamName, targetName, rendered)
      apply(rendered)
      cyclesSinceRebind = 0
      pendingRebind.set(false)
      lastTargetName = targetName
      return
    }

    val changed = rendered.prefix != lastPrefix || rendered.suffix != lastSuffix
    if (changed) {
      teamBridge.updateTeam(viewer, teamName, rendered)
      apply(rendered)
      cyclesSinceRebind = 0
    } else {
      cyclesSinceRebind++
    }

    if (pendingRebind.getAndSet(false) || cyclesSinceRebind >= rebindCycles) {
      teamBridge.rebindEntity(viewer, teamName, targetName)
      cyclesSinceRebind = 0
    }

    lastTargetName = targetName
  }

  fun removeFromViewer(
    viewer: Player,
    objectiveName: String?,
    fallbackTargetName: String,
    bridge: ScoreboardPacketBridge,
    teamBridge: ViewTeamPacketBridge,
  ) {
    if (objectiveName != null) {
      val entryName = lastTargetName.ifBlank { fallbackTargetName }
      if (entryName.isNotBlank()) {
        bridge.removeEntry(viewer, objectiveName, entryName)
      }
      return
    }
    teamBridge.removeTeam(viewer, teamName)
  }

  private fun apply(tag: RenderedTag) {
    lastPrefix = tag.prefix
    lastSuffix = tag.suffix
  }
}

internal data class RenderedTag(
  val prefix: String,
  val suffix: String,
  val below: String,
  val belowScore: Int?,
)
