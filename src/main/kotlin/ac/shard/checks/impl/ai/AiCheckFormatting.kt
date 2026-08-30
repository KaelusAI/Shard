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

import ac.shard.ai.label.LabelKey
import kotlin.math.abs
import kotlin.math.roundToLong

private const val ONE_DECIMAL_SCALE = 10L
private const val TWO_DECIMAL_SCALE = 100L
private const val FOUR_DECIMAL_SCALE = 10_000L
private const val ONE_DECIMAL_DIGITS = 1
private const val TWO_DECIMAL_DIGITS = 2
private const val FOUR_DECIMAL_DIGITS = 4
private const val PROBABILITY_DEBUG_CAPACITY = 64
private const val FLAG_DEBUG_CAPACITY = 32
private const val GENERIC_FORMAT_CAPACITY = 16

internal fun formatAiBuffer(value: Double): String =
  formatFixed(value, ONE_DECIMAL_SCALE, ONE_DECIMAL_DIGITS)

internal fun formatAiProbability(value: Double): String =
  formatFixed(value, TWO_DECIMAL_SCALE, TWO_DECIMAL_DIGITS)

internal fun formatAiProbabilityVerbose(value: Double): String =
  formatFixed(value, FOUR_DECIMAL_SCALE, FOUR_DECIMAL_DIGITS)

internal fun buildAiProbabilityDebugMessage(
  playerName: String,
  probability: Double,
  oldBuffer: Double,
  newBuffer: Double,
  damageMultiplier: Double,
): String {
  return buildString(PROBABILITY_DEBUG_CAPACITY) {
    append('[')
    append(playerName)
    append("] Prob: ")
    append(formatAiProbabilityVerbose(probability))
    append(" | Buffer: ")
    append(formatAiProbability(oldBuffer))
    append(" -> ")
    append(formatAiProbability(newBuffer))
    append(" | Damage Multiplier: ")
    append(formatAiProbability(damageMultiplier))
  }
}

internal fun buildAiFlagDebug(
  probability: Double,
  crossed: Map<String, Double>,
  tracked: Map<String, Double> = emptyMap(),
): String {
  return buildString(FLAG_DEBUG_CAPACITY) {
    append("prob=")
    append(formatAiProbability(probability))
    val attributed = (tracked + crossed).filterKeys { !LabelKey.isReserved(it) }
    val unnamed = crossed.filterKeys { LabelKey.isReserved(it) }.values.maxOrNull()
    if (unnamed != null || attributed.isEmpty()) {
      append(" buffer=")
      append(formatAiBuffer(unnamed ?: crossed.values.maxOrNull() ?: 0.0))
    }
    for ((label, value) in attributed.entries.sortedByDescending { it.value }) {
      append(' ')
      append(label)
      append('=')
      append(formatAiBuffer(value))
    }
  }
}

private fun formatFixed(value: Double, scale: Long, decimals: Int): String {
  val scaled = (value * scale).roundToLong()
  val sign = if (scaled < 0) "-" else ""
  val absoluteScaled = abs(scaled)
  val whole = absoluteScaled / scale
  val fraction = absoluteScaled % scale

  return buildString(GENERIC_FORMAT_CAPACITY) {
    append(sign)
    append(whole)
    if (decimals > 0) {
      append('.')
      append(fraction.toString().padStart(decimals, '0'))
    }
  }
}
