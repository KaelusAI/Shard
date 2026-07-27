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

import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.PING_UNAVAILABLE
import ac.shard.monitor.core.PingSampler
import ac.shard.monitor.core.TrendTracker
import java.util.UUID

data class UnavailableTexts(val noData: String, val noAiCheck: String)

class MonitorTargetState(
  val targetId: UUID,
  val targetName: String,
  val texts: UnavailableTexts,
  threshold: Double,
  decayCycles: Int,
) {
  private val pingSampler = PingSampler()
  private val trendTracker = TrendTracker(threshold, decayCycles)

  var ping = PING_UNAVAILABLE
    private set

  var trend = 0.0
    private set

  fun advance(sample: MonitorSample, refreshCycles: Int, bucketMs: Int) {
    ping = pingSampler.sampleValue(sample.rawPing, refreshCycles, bucketMs)
    trend = trendTracker.update(sample.probability)
  }
}

class MonitorTargets {
  private val states = LinkedHashMap<UUID, MonitorTargetState>()

  val size: Int
    get() = states.size

  fun ids(): List<UUID> = states.keys.toList()

  fun names(): List<String> = states.values.map { it.targetName }

  fun all(): List<MonitorTargetState> = states.values.toList()

  fun state(targetId: UUID): MonitorTargetState? = states[targetId]

  fun add(state: MonitorTargetState): Boolean = states.putIfAbsent(state.targetId, state) == null

  fun remove(targetId: UUID): Boolean = states.remove(targetId) != null
}

internal fun withinLimits(
  limits: MonitorLimitsConfig,
  sessionCount: Int,
  viewersOfTarget: Int,
): Boolean {
  val roomForSession = limits.maxSessions <= 0 || sessionCount < limits.maxSessions
  val roomOnTarget = limits.maxViewersPerTarget <= 0 || viewersOfTarget < limits.maxViewersPerTarget
  return roomForSession && roomOnTarget
}

internal fun outputCapacity(output: MonitorOutput, config: MonitorHudRuntimeConfig): Int {
  val declared = output.capabilities.maxTargets
  val configured =
    if (output.kind == MonitorOutputKind.BOSSBAR) config.bossBar.maxBars else declared
  return minOf(declared, configured).coerceAtLeast(1)
}

internal fun effectiveCapacity(outputs: List<MonitorOutput>, config: MonitorHudRuntimeConfig): Int =
  outputs.maxOfOrNull { outputCapacity(it, config) } ?: 1
