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
package ac.shard.utils.nmsutil

import ac.shard.entity.PacketEntity
import ac.shard.utils.math.SimpleCollisionBox
import kotlin.math.max
import kotlin.math.min

object GetBoundingBox {

  fun getPacketEntityBoundingBox(
    centerX: Double,
    minY: Double,
    centerZ: Double,
    entity: PacketEntity,
  ): SimpleCollisionBox {
    val width = BoundingBoxSize.getWidth(entity)
    val height = BoundingBoxSize.getHeight(entity)
    return fromPosAndSize(centerX, minY, centerZ, width, height)
  }

  fun fromPosAndSize(
    centerX: Double,
    minY: Double,
    centerZ: Double,
    width: Float,
    height: Float,
  ): SimpleCollisionBox {
    val halfWidth = width / 2.0
    val minX = centerX - halfWidth
    val maxX = centerX + halfWidth
    val maxY = minY + height
    val minZ = centerZ - halfWidth
    val maxZ = centerZ + halfWidth
    return SimpleCollisionBox(
      min(minX, maxX),
      min(minY, maxY),
      min(minZ, maxZ),
      max(minX, maxX),
      max(minY, maxY),
      max(minZ, maxZ),
    )
  }

  fun expandBoundingBoxByEntityDimensions(box: SimpleCollisionBox, entity: PacketEntity) {
    val width = BoundingBoxSize.getWidth(entity).toDouble()
    val height = BoundingBoxSize.getHeight(entity).toDouble()
    val halfWidth = width / 2.0

    val minX = box.minX - halfWidth
    val minY = box.minY
    val minZ = box.minZ - halfWidth
    val maxX = box.maxX + halfWidth
    val maxY = box.maxY + height
    val maxZ = box.maxZ + halfWidth

    box.minX = min(minX, maxX)
    box.minY = min(minY, maxY)
    box.minZ = min(minZ, maxZ)
    box.maxX = max(minX, maxX)
    box.maxY = max(minY, maxY)
    box.maxZ = max(minZ, maxZ)
  }
}
