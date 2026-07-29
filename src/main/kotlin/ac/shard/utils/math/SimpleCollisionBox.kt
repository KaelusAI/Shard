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
import kotlin.math.max
import kotlin.math.min

@Suppress("TooManyFunctions")
class SimpleCollisionBox(
  var minX: Double,
  var minY: Double,
  var minZ: Double,
  var maxX: Double,
  var maxY: Double,
  var maxZ: Double,
) {

  constructor() : this(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

  constructor(min: Vector3d, max: Vector3d) : this(min.x, min.y, min.z, max.x, max.y, max.z)

  constructor(min: Vector3dm, max: Vector3dm) : this(min.x, min.y, min.z, max.x, max.y, max.z)

  fun expand(x: Double, y: Double, z: Double): SimpleCollisionBox {
    minX -= x
    minY -= y
    minZ -= z
    maxX += x
    maxY += y
    maxZ += z
    return sort()
  }

  fun expand(value: Double): SimpleCollisionBox {
    minX -= value
    minY -= value
    minZ -= value
    maxX += value
    maxY += value
    maxZ += value
    return this
  }

  fun expandMin(x: Double, y: Double, z: Double): SimpleCollisionBox {
    minX += x
    minY += y
    minZ += z
    return this
  }

  fun expandMax(x: Double, y: Double, z: Double): SimpleCollisionBox {
    maxX += x
    maxY += y
    maxZ += z
    return this
  }

  fun sort(): SimpleCollisionBox {
    val sMinX = min(minX, maxX)
    val sMinY = min(minY, maxY)
    val sMinZ = min(minZ, maxZ)
    val sMaxX = max(minX, maxX)
    val sMaxY = max(minY, maxY)
    val sMaxZ = max(minZ, maxZ)
    minX = sMinX
    minY = sMinY
    minZ = sMinZ
    maxX = sMaxX
    maxY = sMaxY
    maxZ = sMaxZ
    return this
  }

  fun offset(x: Double, y: Double, z: Double): SimpleCollisionBox {
    minX += x
    minY += y
    minZ += z
    maxX += x
    maxY += y
    maxZ += z
    return this
  }

  fun copy(): SimpleCollisionBox = SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ)

  fun encompass(other: SimpleCollisionBox): SimpleCollisionBox {
    minX = min(minX, other.minX)
    minY = min(minY, other.minY)
    minZ = min(minZ, other.minZ)
    maxX = max(maxX, other.maxX)
    maxY = max(maxY, other.maxY)
    maxZ = max(maxZ, other.maxZ)
    return this
  }

  override fun toString(): String =
    "SimpleCollisionBox(min=[$minX, $minY, $minZ], max=[$maxX, $maxY, $maxZ])"

  companion object {
    fun combine(a: SimpleCollisionBox, b: SimpleCollisionBox): SimpleCollisionBox =
      SimpleCollisionBox(
        min(a.minX, b.minX),
        min(a.minY, b.minY),
        min(a.minZ, b.minZ),
        max(a.maxX, b.maxX),
        max(a.maxY, b.maxY),
        max(a.maxZ, b.maxZ),
      )
  }

  fun distanceX(x: Double): Double =
    if (x >= minX && x <= maxX) 0.0 else minOf(kotlin.math.abs(x - minX), kotlin.math.abs(x - maxX))

  fun distanceY(y: Double): Double =
    if (y >= minY && y <= maxY) 0.0 else minOf(kotlin.math.abs(y - minY), kotlin.math.abs(y - maxY))

  fun distanceZ(z: Double): Double =
    if (z >= minZ && z <= maxZ) 0.0 else minOf(kotlin.math.abs(z - minZ), kotlin.math.abs(z - maxZ))
}
