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

import java.util.Locale
import kotlin.math.abs

private const val DECIMAL_EPSILON = 0.0000001

internal fun formatDecimal(value: Double, decimals: Int): String {
  val safeDecimals = decimals.coerceAtLeast(0)
  val normalized = if (abs(value) < DECIMAL_EPSILON) 0.0 else value
  return String.format(Locale.US, "%.${safeDecimals}f", normalized)
}
