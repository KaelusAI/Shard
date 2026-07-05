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

class TickRingBuffer(sequence: Int) {
  val capacity: Int = maxOf(sequence, MIN_SEQUENCE)

  private val data = FloatArray(capacity * FEATURES_PER_TICK)
  private var head = 0

  var count: Int = 0
    private set

  var ticksStep: Int = 0
    private set

  fun push(deltaYaw: Float, deltaPitch: Float) {
    var writeSlot = head + count
    if (writeSlot >= capacity) writeSlot -= capacity

    val base = writeSlot * FEATURES_PER_TICK
    data[base] = deltaYaw
    data[base + 1] = deltaPitch

    if (count == capacity) {
      head++
      if (head >= capacity) head -= capacity
    } else {
      count++
    }

    ticksStep++
  }

  fun reset() {
    count = 0
    head = 0
    ticksStep = 0
  }

  fun markSent() {
    ticksStep = 0
  }

  fun isFull(): Boolean = count == capacity

  fun canSend(step: Int): Boolean = count == capacity && ticksStep >= step

  fun snapshotInto(out: FloatArray): Int {
    val floatCount = count * FEATURES_PER_TICK
    val srcStart = head * FEATURES_PER_TICK

    val firstFloats = minOf(capacity - head, count) * FEATURES_PER_TICK
    System.arraycopy(data, srcStart, out, 0, firstFloats)

    val remaining = floatCount - firstFloats
    if (remaining > 0) {
      System.arraycopy(data, 0, out, firstFloats, remaining)
    }
    return count
  }

  companion object {
    const val MIN_SEQUENCE = 1
    private const val FEATURES_PER_TICK = 2
  }
}
