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
package ac.shard.utils.math

import com.github.retrooper.packetevents.util.Vector3d
import kotlin.math.sqrt

class Vector3dm(var x: Double, var y: Double, var z: Double) {

  fun setY(value: Double): Vector3dm {
    y = value
    return this
  }

  fun add(dx: Double, dy: Double, dz: Double): Vector3dm {
    x += dx
    y += dy
    z += dz
    return this
  }

  fun clone(): Vector3dm = Vector3dm(x, y, z)

  fun distanceSquared(other: Vector3dm): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return dx * dx + dy * dy + dz * dz
  }

  fun distanceSquared(ox: Double, oy: Double, oz: Double): Double {
    val dx = x - ox
    val dy = y - oy
    val dz = z - oz
    return dx * dx + dy * dy + dz * dz
  }

  fun distance(ox: Double, oy: Double, oz: Double): Double = sqrt(distanceSquared(ox, oy, oz))

  fun toVector3d(): Vector3d = Vector3d(x, y, z)

  override fun toString(): String = "Vector3dm($x, $y, $z)"
}
