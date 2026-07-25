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
package ac.shard.monitor.core

import kotlin.math.abs

class TrendTracker(private val threshold: Double, private val decayCycles: Int) {
  private var lastProbability = UNSET_PROBABILITY
  private var trend = 0.0
  private var cyclesSinceChange = 0

  fun update(probability: Double): Double {
    if (lastProbability >= 0.0) {
      applyDelta(probability - lastProbability)
    }
    lastProbability = probability
    return trend
  }

  fun reset() {
    lastProbability = UNSET_PROBABILITY
    trend = 0.0
    cyclesSinceChange = 0
  }

  private fun applyDelta(delta: Double) {
    if (abs(delta) > PROBABILITY_EPSILON) {
      trend = if (abs(delta) < threshold) 0.0 else delta
      cyclesSinceChange = 0
      return
    }
    cyclesSinceChange++
    if (decayCycles > 0 && cyclesSinceChange >= decayCycles) {
      trend = 0.0
    }
  }

  private companion object {
    const val UNSET_PROBABILITY = -1.0
    const val PROBABILITY_EPSILON = 0.0001
  }
}
