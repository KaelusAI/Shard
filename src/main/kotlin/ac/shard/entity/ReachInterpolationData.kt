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

import ac.shard.player.ShardPlayer
import ac.shard.utils.math.SimpleCollisionBox
import ac.shard.utils.nmsutil.GetBoundingBox
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import kotlin.math.min

class ReachInterpolationData {
  var startingLocation: SimpleCollisionBox
  private val targetLocation: SimpleCollisionBox
  private val entity: PacketEntity
  private var interpolationStepsLowBound: Int = 0
  private var interpolationStepsHighBound: Int = 0
  private var interpolationSteps: Int = 1
  private var expandNonRelative: Boolean = false

  constructor(
    player: ShardPlayer,
    startingLocation: SimpleCollisionBox,
    position: TrackedPosition,
    entity: PacketEntity,
  ) {
    val canSkipTicks = player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_9)
    val unreliableTicking = player.compensatedEntities.self.riding == null && canSkipTicks

    this.startingLocation = startingLocation
    val pos = position.pos
    this.targetLocation = SimpleCollisionBox(pos.x, pos.y, pos.z, pos.x, pos.y, pos.z)
    this.entity = entity

    if (
      player.user.clientVersion.isOlderThan(ClientVersion.V_1_9) &&
        PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_9)
    ) {
      targetLocation.expand(0.03125)
    }

    interpolationSteps =
      when {
        entity.isBoat -> 10
        entity.isMinecart -> 5
        entity.type == EntityTypes.SHULKER -> 1
        entity.isLivingEntity -> 3
        else -> 1
      }

    if (unreliableTicking) interpolationStepsHighBound = interpolationSteps
  }

  constructor(startingLocation: SimpleCollisionBox, entity: PacketEntity) {
    this.startingLocation = startingLocation
    this.targetLocation = startingLocation
    this.entity = entity
  }

  fun getPossibleLocationCombined(): SimpleCollisionBox {
    val steps = interpolationSteps.toDouble()

    val stepMinX = (targetLocation.minX - startingLocation.minX) / steps
    val stepMaxX = (targetLocation.maxX - startingLocation.maxX) / steps
    val stepMinY = (targetLocation.minY - startingLocation.minY) / steps
    val stepMaxY = (targetLocation.maxY - startingLocation.maxY) / steps
    val stepMinZ = (targetLocation.minZ - startingLocation.minZ) / steps
    val stepMaxZ = (targetLocation.maxZ - startingLocation.maxZ) / steps

    var result =
      boxAtStep(
        interpolationStepsLowBound,
        stepMinX,
        stepMaxX,
        stepMinY,
        stepMaxY,
        stepMinZ,
        stepMaxZ,
      )

    for (step in (interpolationStepsLowBound + 1)..interpolationStepsHighBound) {
      result =
        SimpleCollisionBox.combine(
          result,
          boxAtStep(step, stepMinX, stepMaxX, stepMinY, stepMaxY, stepMinZ, stepMaxZ),
        )
    }
    return result
  }

  fun getPossibleHitboxCombined(): SimpleCollisionBox {
    val combined = getPossibleLocationCombined()
    if (expandNonRelative) combined.expand(0.03125, 0.015625, 0.03125)
    GetBoundingBox.expandBoundingBoxByEntityDimensions(combined, entity)
    return combined
  }

  fun tickMovement(incrementLowBound: Boolean, tickingReliably: Boolean) {
    if (!tickingReliably) interpolationStepsHighBound = interpolationSteps
    if (incrementLowBound)
      interpolationStepsLowBound = min(interpolationStepsLowBound + 1, interpolationSteps)
    interpolationStepsHighBound = min(interpolationStepsHighBound + 1, interpolationSteps)
  }

  fun updatePossibleStartingLocation(possibleLocationCombined: SimpleCollisionBox) {
    startingLocation = SimpleCollisionBox.combine(startingLocation, possibleLocationCombined)
  }

  fun expandNonRelative() {
    expandNonRelative = true
  }

  private fun boxAtStep(
    step: Int,
    stepMinX: Double,
    stepMaxX: Double,
    stepMinY: Double,
    stepMaxY: Double,
    stepMinZ: Double,
    stepMaxZ: Double,
  ): SimpleCollisionBox =
    SimpleCollisionBox(
      startingLocation.minX + step * stepMinX,
      startingLocation.minY + step * stepMinY,
      startingLocation.minZ + step * stepMinZ,
      startingLocation.maxX + step * stepMaxX,
      startingLocation.maxY + step * stepMaxY,
      startingLocation.maxZ + step * stepMaxZ,
    )
}
