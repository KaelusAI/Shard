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

const val TRAIL_CAPACITY = 1000

private const val STEPS = 255.0
private const val BYTE_MASK = 0xFF

class ProbabilityTrail(private val capacity: Int = TRAIL_CAPACITY) {

  private val points = ByteArray(capacity)
  private var written = 0L

  @Synchronized
  fun record(probability: Double) {
    points[(written % capacity).toInt()] = quantize(probability)
    written++
  }

  @Synchronized
  fun clear() {
    written = 0L
  }

  @Synchronized
  fun tail(length: Int): ByteArray {
    val size = minOf(length, capacity, written.toInt().coerceAtLeast(0))
    if (written <= 0L || size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    val start = written - size
    for (i in 0 until size) {
      out[i] = points[((start + i) % capacity).toInt()]
    }
    return out
  }

  companion object {
    fun quantize(probability: Double): Byte =
      (probability.coerceIn(0.0, 1.0) * STEPS).toInt().toByte()

    fun probabilityOf(point: Byte): Double = (point.toInt() and BYTE_MASK) / STEPS
  }
}
