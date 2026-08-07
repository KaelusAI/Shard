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
package ac.shard.checks.impl.ai

import kotlin.math.max

class ViolationBuffer {
  @Volatile
  var value: Double = 0.0
    private set

  fun feed(probability: Double, settings: Settings): Double {
    val previous = value
    if (probability > settings.cheatProbability) {
      value = previous + (probability - settings.cheatProbability) * settings.multiplier
    } else if (probability < settings.legitProbability) {
      value = max(0.0, previous - settings.decrease)
    }
    return previous
  }

  fun consumeFlag(resetTo: Double): Boolean {
    value = resetTo
    return true
  }

  fun restore(saved: Double) {
    value = max(value, max(0.0, saved))
  }

  fun clear() {
    value = 0.0
  }

  class Settings(
    val cheatProbability: Double,
    val legitProbability: Double,
    val multiplier: Double,
    val decrease: Double,
  )
}
