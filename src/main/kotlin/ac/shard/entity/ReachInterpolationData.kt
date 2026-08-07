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
import kotlin.math.max
import kotlin.math.min

class ReachInterpolationData {
  var startingLocation: SimpleCollisionBox
  private val targetLocation: SimpleCollisionBox
  private val entity: PacketEntity
  private var interpolationStepsLowBound: Int = 0
  private var interpolationStepsHighBound: Int = 0
  private var interpolationSteps: Int = 1
  private var expandNonRelative: Boolean = false

  private var maxOffsetX: Double = 0.0
  private var maxOffsetY: Double = 0.0
  private var maxOffsetZ: Double = 0.0
  private var hasMaxOffset: Boolean = false
  private var teleportActive: Boolean = false

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

    interpolationSteps = interpolationStepsFor(entity, player.user.clientVersion)

    if (unreliableTicking) interpolationStepsHighBound = interpolationSteps

    buildMaxOffset(player.user.clientVersion)
    clampStartToTarget()
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
    clampStartToTarget()
  }

  fun expandNonRelative() {
    expandNonRelative = true
  }

  private fun buildMaxOffset(clientVersion: ClientVersion) {
    val steps = interpolationSteps
    val legacy =
      clientVersion.isOlderThan(ClientVersion.V_1_9) &&
        PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_9)
    val jitterXZ = if (legacy) LEGACY_JITTER_XZ else JITTER
    val jitterY = if (legacy) LEGACY_JITTER_Y else JITTER

    var offsetX = steps * max(entity.interpVelEstimate[0], VELOCITY_FLOOR_XZ) * SAFETY + jitterXZ
    var offsetY = steps * max(entity.interpVelEstimate[1], VELOCITY_FLOOR_Y) * SAFETY + jitterY
    var offsetZ = steps * max(entity.interpVelEstimate[2], VELOCITY_FLOOR_XZ) * SAFETY + jitterXZ

    val jump = entity.lastTeleportJump
    if (jump != null) {
      offsetX = max(offsetX, jump[0] + jitterXZ)
      offsetY = max(offsetY, jump[1] + jitterY)
      offsetZ = max(offsetZ, jump[2] + jitterXZ)
      teleportActive = true
    }
    entity.lastTeleportJump = null

    maxOffsetX = offsetX
    maxOffsetY = offsetY
    maxOffsetZ = offsetZ
    hasMaxOffset = true
  }

  private fun clampStartToTarget() {
    if (!hasMaxOffset) return

    val box = startingLocation
    val centerX = (targetLocation.minX + targetLocation.maxX) * HALF
    val centerY = (targetLocation.minY + targetLocation.maxY) * HALF
    val centerZ = (targetLocation.minZ + targetLocation.maxZ) * HALF

    var minX = max(box.minX, centerX - maxOffsetX)
    var maxX = min(box.maxX, centerX + maxOffsetX)
    var minY = max(box.minY, centerY - maxOffsetY)
    var maxY = min(box.maxY, centerY + maxOffsetY)
    var minZ = max(box.minZ, centerZ - maxOffsetZ)
    var maxZ = min(box.maxZ, centerZ + maxOffsetZ)

    if (minX > maxX) {
      minX = if (teleportActive) box.minX else centerX
      maxX = if (teleportActive) box.maxX else centerX
    }
    if (minY > maxY) {
      minY = if (teleportActive) box.minY else centerY
      maxY = if (teleportActive) box.maxY else centerY
    }
    if (minZ > maxZ) {
      minZ = if (teleportActive) box.minZ else centerZ
      maxZ = if (teleportActive) box.maxZ else centerZ
    }

    box.minX = minX
    box.minY = minY
    box.minZ = minZ
    box.maxX = maxX
    box.maxY = maxY
    box.maxZ = maxZ
  }

  companion object {
    fun interpolationStepsFor(entity: PacketEntity, clientVersion: ClientVersion): Int =
      when {
        entity.isBoat ->
          if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_2)) GENERIC_STEPS
          else BOAT_STEPS
        entity.isMinecart -> MINECART_STEPS
        entity.type == EntityTypes.SHULKER -> 1
        entity.isLivingEntity -> GENERIC_STEPS
        else -> 1
      }

    private const val BOAT_STEPS = 10
    private const val MINECART_STEPS = 5
    private const val GENERIC_STEPS = 3
    private const val HALF = 0.5
    private const val VELOCITY_FLOOR_XZ = 0.05
    private const val VELOCITY_FLOOR_Y = 0.08
    private const val JITTER = 1e-3
    private const val LEGACY_JITTER_XZ = 0.03125
    private const val LEGACY_JITTER_Y = 0.015625
    private const val SAFETY = 1.1
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
