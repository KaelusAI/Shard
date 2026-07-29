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
package ac.shard.utils.math

object VanillaMath {
  private const val SIN_SCALE_LEGACY = 10430.378f
  private const val SIN_SCALE = 10430.378350470453
  private const val COS_OFFSET = 16384
  private const val TABLE_MASK = 65535
  private const val TABLE_SIZE = 65536

  // sin(i * 2pi / 65536) == sin(i / 10430.378350470453) bit-for-bit in float32
  private val SIN = FloatArray(TABLE_SIZE) { i -> Math.sin(i / SIN_SCALE).toFloat() }

  fun sin(radians: Float, doubleIndex: Boolean): Float = SIN[index(radians, 0.0, doubleIndex)]

  fun cos(radians: Float, doubleIndex: Boolean): Float =
    SIN[index(radians, COS_OFFSET.toDouble(), doubleIndex)]

  private fun index(radians: Float, offset: Double, doubleIndex: Boolean): Int =
    if (doubleIndex) {
      ((radians * SIN_SCALE + offset).toLong() and TABLE_MASK.toLong()).toInt()
    } else {
      (radians * SIN_SCALE_LEGACY + offset.toFloat()).toInt() and TABLE_MASK
    }
}
