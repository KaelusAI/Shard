/*
 * This file is part of GrimAC - https://github.com/GrimAnticheat/Grim
 * Copyright (C) 2021-2026 GrimAC, DefineOutside and contributors
 *
 * GrimAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.entity

import com.github.retrooper.packetevents.util.Vector3d
import kotlin.math.floor
import kotlin.math.roundToLong

class TrackedPosition {

  var pos: Vector3d = Vector3d()

  // Method since 1.16.
  fun withDelta(x: Double, y: Double, z: Double): Vector3d = withDelta(pack(x), pack(y), pack(z))

  fun withDelta(x: Long, y: Long, z: Long): Vector3d {
    if (x == 0L && y == 0L && z == 0L) return pos
    val d = if (x == 0L) pos.x else unpack(pack(pos.x) + x)
    val e = if (y == 0L) pos.y else unpack(pack(pos.y) + y)
    val f = if (z == 0L) pos.z else unpack(pack(pos.z) + z)
    return Vector3d(d, e, f)
  }

  // Before 1.19.3 the client packed with floor instead of round (ClientboundMoveEntityPacket
  // .entityToPacket), and 1.16- kept the position packed instead of re-packing it every packet.
  fun withDeltaLegacy(x: Double, y: Double, z: Double): Vector3d {
    if (x == 0.0 && y == 0.0 && z == 0.0) return pos
    val d = if (x == 0.0) pos.x else unpackLegacy(packLegacy(pos.x) + packLegacy(x))
    val e = if (y == 0.0) pos.y else unpackLegacy(packLegacy(pos.y) + packLegacy(y))
    val f = if (z == 0.0) pos.z else unpackLegacy(packLegacy(pos.z) + packLegacy(z))
    return Vector3d(d, e, f)
  }

  private fun pack(value: Double): Long = (value * COORDINATE_SCALE).roundToLong()

  private fun unpack(value: Long): Double = value.toDouble() / COORDINATE_SCALE

  private fun packLegacy(value: Double): Double = floor(value * COORDINATE_SCALE)

  private fun unpackLegacy(value: Double): Double = value / COORDINATE_SCALE

  private companion object {
    const val COORDINATE_SCALE = 4096.0
  }
}
