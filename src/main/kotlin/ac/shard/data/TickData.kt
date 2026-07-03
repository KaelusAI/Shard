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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.data

import ac.shard.player.ShardPlayer
import kotlin.math.roundToLong

class TickData(shardPlayer: ShardPlayer) {
  val deltaYaw: Float = shardPlayer.movement.yaw - shardPlayer.movement.lastYaw
  val deltaPitch: Float = shardPlayer.movement.pitch - shardPlayer.movement.lastPitch

  fun toCsv(status: String): String {
    return buildString { appendCsv(this, status) }
  }

  fun appendCsv(out: Appendable, status: String) {
    val cheatingStatus = if (status.equals("CHEAT", ignoreCase = true)) 1 else 0
    out.append(if (cheatingStatus == 1) '1' else '0')
    out.append(',')
    appendFixed6(out, deltaYaw)
    out.append(',')
    appendFixed6(out, deltaPitch)
  }

  companion object {
    const val HEADER = "is_cheating,delta_yaw,delta_pitch"

    private const val SCALE = 1_000_000L

    private fun appendFixed6(out: Appendable, value: Float) {
      if (!value.isFinite()) {
        out.append("0.000000")
        return
      }

      var v = value.toDouble()
      val negative = v < 0.0
      if (negative) {
        v = -v
      }
      val scaled = (v * SCALE).roundToLong()
      val integerPart = scaled / SCALE
      val fractionPart = (scaled % SCALE).toInt()

      if (negative) {
        out.append('-')
      }
      out.append(integerPart.toString())
      out.append('.')
      val fractionText = fractionPart.toString()
      for (i in fractionText.length until 6) {
        out.append('0')
      }
      out.append(fractionText)
    }
  }
}
