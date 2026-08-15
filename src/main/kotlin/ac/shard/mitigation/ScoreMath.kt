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
package ac.shard.mitigation

import kotlin.math.ln
import kotlin.math.pow

data class ScoreSettings(
  val neutral: Double,
  val clampLow: Double,
  val clampHigh: Double,
  val halfLifeMillis: Long,
  val floor: Double,
  val ceiling: Double,
  val minAnswers: Long,
  val forgetRate: Double,
  val persistEnabled: Boolean,
  val persistTtlMillis: Long,
  val capOnRestore: Double,
)

object ScoreMath {

  const val LOW_TAIL_UNTIL = 0.2
  const val SPIKE_FROM = 0.9
  const val HISTOGRAM_BINS = 20

  fun bin(probability: Double): Int =
    (probability * HISTOGRAM_BINS).toInt().coerceIn(0, HISTOGRAM_BINS - 1)

  fun logit(probability: Double): Double = ln(probability / (1.0 - probability))

  fun contribution(probability: Double, step: Int, sequence: Int, settings: ScoreSettings): Double {
    if (!probability.isFinite()) return 0.0
    val clamped = probability.coerceIn(settings.clampLow, settings.clampHigh)
    val share = if (sequence <= 0) 1.0 else (step.toDouble() / sequence).coerceIn(0.0, 1.0)
    return share * (logit(clamped) - logit(settings.neutral))
  }

  fun decay(value: Double, elapsedMillis: Long, halfLifeMillis: Long): Double {
    if (elapsedMillis <= 0L || halfLifeMillis <= 0L) return value
    return value * 0.5.pow(elapsedMillis.toDouble() / halfLifeMillis)
  }

  fun clampToRange(value: Double, settings: ScoreSettings): Double =
    value.coerceIn(settings.floor, settings.ceiling)
}
