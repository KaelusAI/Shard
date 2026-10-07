/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
package ac.shard.packet

import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying

internal object DuplicateFlyingFilter {
  fun apply(player: ShardPlayer, flying: WrapperPlayClientPlayerFlying, event: PacketReceiveEvent) {
    if (
      player.packetStateData.lastPacketWasTeleport ||
        player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21)
    ) {
      return
    }

    val location: Location = flying.location
    if (isDuplicate(player, flying, location)) {
      handle(player, flying, location, event)
      player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = true
      applyRotation(player, location)
    }
  }

  private fun isDuplicate(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
    location: Location,
  ): Boolean {
    val threshold = player.getMovementThreshold()
    val inVehicle = player.compensatedEntities.self.riding != null
    val hasMovementAndRotation = flying.hasPositionChanged() && flying.hasRotationChanged()
    val sameGroundAndCloseClaim =
      flying.isOnGround == player.packetStateData.packetPlayerOnGround &&
        player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_17) &&
        player.packetStateData.duplicatePacketFilterPosition.distanceSquared(location.position) <
          threshold * threshold
    return hasMovementAndRotation && (sameGroundAndCloseClaim || inVehicle)
  }

  private fun handle(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
    location: Location,
    event: PacketReceiveEvent,
  ) {
    val serverVersion = PacketEvents.getAPI().serverManager.version
    val packets = player.packets
    if (packets.forceCancelDuplicate) {
      event.isCancelled = true
      return
    }

    if (serverVersion.isOlderThanOrEquals(ServerVersion.V_1_9)) {
      if (packets.cancelDuplicate) {
        event.isCancelled = true
      }
      return
    }

    flying.location =
      Location(player.packetStateData.duplicatePacketFilterPosition, location.yaw, location.pitch)
    event.markForReEncode(true)
  }

  private fun applyRotation(player: ShardPlayer, location: Location) {
    if (player.packets.ignoreDuplicateRotation) return

    val movement = player.movement
    if (movement.yaw != location.yaw || movement.pitch != location.pitch) {
      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
    }
    movement.yaw = location.yaw
    movement.pitch = location.pitch
  }
}
