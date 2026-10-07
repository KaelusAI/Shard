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
package ac.shard.punishment

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.round

private const val TICKS_PER_SECOND = 20.0
private const val MILLIS_PER_TICK = 50.0
private const val SECONDS_PER_MINUTE = 60.0
private const val SECONDS_PER_HOUR = 3600.0

internal fun parseWaitTicks(raw: String): Long? {
  val value = raw.trim().lowercase(Locale.ROOT)
  val ticks =
    when {
      value.endsWith("ms") -> value.dropLast(2).toDoubleOrNull()?.let { ceil(it / MILLIS_PER_TICK) }
      value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()?.let { it * TICKS_PER_SECOND }
      value.endsWith("m") ->
        value.dropLast(1).toDoubleOrNull()?.let { it * SECONDS_PER_MINUTE * TICKS_PER_SECOND }
      value.endsWith("h") ->
        value.dropLast(1).toDoubleOrNull()?.let { it * SECONDS_PER_HOUR * TICKS_PER_SECOND }
      value.endsWith("t") -> value.dropLast(1).toDoubleOrNull()
      else -> value.toDoubleOrNull()
    }
  return ticks?.takeIf { it.isFinite() && it >= 0.0 }?.let { round(it).toLong() }
}
