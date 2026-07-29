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
 * GNU General Public License for more details
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.entity

import ac.shard.player.ShardPlayer
import ac.shard.utils.math.SimpleCollisionBox
import ac.shard.utils.nmsutil.GetBoundingBox
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.util.Vector3d
import java.util.UUID

open class PacketEntity(val player: ShardPlayer, val uuid: UUID, val type: EntityType) {
  val isPlayer: Boolean = type == EntityTypes.PLAYER
  val isLivingEntity: Boolean = EntityTypes.isTypeInstanceOf(type, EntityTypes.LIVINGENTITY)
  val isBoat: Boolean = EntityTypes.isTypeInstanceOf(type, EntityTypes.BOAT)
  val isMinecart: Boolean = EntityTypes.isTypeInstanceOf(type, EntityTypes.MINECART_ABSTRACT)

  @Volatile var riding: PacketEntity? = null

  val trackedServerPosition: TrackedPosition = TrackedPosition()

  var newPacketLocation: ReachInterpolationData? = null
    private set

  var oldPacketLocation: ReachInterpolationData? = null
    private set

  var isBaby: Boolean = false
  var isDead: Boolean = false
  var slimeSize: Int = 1
  var phantomSize: Int = 0
  var scale: Float = 1.0f
  var pose: Int = 0

  var lastTransactionHung: Int = 0

  fun canHit(): Boolean = !isDead

  fun mount(vehicle: PacketEntity) {
    riding = vehicle
  }

  fun eject() {
    riding = null
  }

  @Suppress("LongParameterList")
  fun onFirstTransaction(
    relative: Boolean,
    hasPos: Boolean,
    deltaX: Double,
    deltaY: Double,
    deltaZ: Double,
    player: ShardPlayer,
  ) {
    val old = newPacketLocation
    val startBox =
      if (old != null) {
        old.getPossibleLocationCombined()
      } else {
        val pos = trackedServerPosition.pos
        SimpleCollisionBox(pos.x, pos.y, pos.z, pos.x, pos.y, pos.z)
      }

    if (hasPos) {
      trackedServerPosition.pos =
        if (relative) {
          if (player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_19_3)) {
            trackedServerPosition.withDelta(deltaX, deltaY, deltaZ)
          } else {
            trackedServerPosition.withDeltaLegacy(deltaX, deltaY, deltaZ)
          }
        } else if (player.user.clientVersion.isOlderThan(ClientVersion.V_1_9)) {
          // ViaVersion desync's here for teleports
          // It simply teleports the entity with its position divided by 32... ignoring the offset
          // this causes.
          Vector3d(
            (deltaX * LEGACY_TELEPORT_SCALE).toInt() / LEGACY_TELEPORT_SCALE,
            (deltaY * LEGACY_TELEPORT_SCALE).toInt() / LEGACY_TELEPORT_SCALE,
            (deltaZ * LEGACY_TELEPORT_SCALE).toInt() / LEGACY_TELEPORT_SCALE,
          )
        } else {
          Vector3d(deltaX, deltaY, deltaZ)
        }
    }

    oldPacketLocation = old
    newPacketLocation =
      if (!hasPos && rotationFreezesInterpolation(player.user.clientVersion)) {
        ReachInterpolationData(startBox, this)
      } else {
        ReachInterpolationData(player, startBox, trackedServerPosition, this)
      }
    if (hasPos && !relative) {
      applyNonRelativeStall(deltaX, deltaY, deltaZ)
    }
  }

  // In versions < 1.16.2 when the client receives non-relative teleport for an entity
  // And they move less by the thresholds given, the entity does not move client side
  private fun applyNonRelativeStall(x: Double, y: Double, z: Double) {
    if (player.user.clientVersion.isNewerThan(ClientVersion.V_1_16_1)) return
    val area = newPacketLocation?.getPossibleLocationCombined() ?: return
    val stalled =
      area.distanceX(x) < NON_RELATIVE_THRESHOLD_XZ &&
        area.distanceY(y) < NON_RELATIVE_THRESHOLD_Y &&
        area.distanceZ(z) < NON_RELATIVE_THRESHOLD_XZ
    if (stalled) newPacketLocation?.expandNonRelative()
  }

  // Bug fix for https://bugs.mojang.com/browse/MC-255263
  private fun rotationFreezesInterpolation(clientVersion: ClientVersion): Boolean =
    (clientVersion.isOlderThan(ClientVersion.V_1_21_9) &&
      clientVersion.isNewerThan(ClientVersion.V_1_21_4)) ||
      (clientVersion.isOlderThan(ClientVersion.V_1_20_2) &&
        clientVersion.isNewerThan(ClientVersion.V_1_14_4))

  fun onSecondTransaction() {
    oldPacketLocation = null
  }

  fun onMovement(tickingReliably: Boolean) {
    val new = newPacketLocation ?: return
    new.tickMovement(
      incrementLowBound = oldPacketLocation == null,
      tickingReliably = tickingReliably,
    )
    val old = oldPacketLocation
    if (old != null) {
      old.tickMovement(incrementLowBound = true, tickingReliably = tickingReliably)
      new.updatePossibleStartingLocation(old.getPossibleLocationCombined())
    }
  }

  private companion object {
    const val LEGACY_TELEPORT_SCALE = 32.0
    const val NON_RELATIVE_THRESHOLD_XZ = 0.03125
    const val NON_RELATIVE_THRESHOLD_Y = 0.015625
  }

  fun getPossibleCollisionBoxes(): SimpleCollisionBox {
    val newBox = newPacketLocation?.getPossibleHitboxCombined()
    val oldBox = oldPacketLocation?.getPossibleHitboxCombined()
    return when {
      newBox != null && oldBox != null -> SimpleCollisionBox.combine(newBox, oldBox)
      newBox != null -> newBox
      oldBox != null -> oldBox
      else -> {
        val pos = trackedServerPosition.pos
        GetBoundingBox.getPacketEntityBoundingBox(pos.x, pos.y, pos.z, this)
      }
    }
  }
}
