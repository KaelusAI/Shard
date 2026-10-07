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

import ac.shard.data.CollectManager
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.player.ShardPlayer
import ac.shard.utils.update.RotationUpdate
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying

class FlyingProcessor(
  private val collectManager: CollectManager,
  private val debugManager: DebugManager,
) {
  fun handle(event: PacketReceiveEvent, player: ShardPlayer) {
    val flying = WrapperPlayClientPlayerFlying(event)

    val teleported = ServerTeleports.acceptTeleport(player, flying)
    val serverRotated = !teleported && ServerTeleports.acceptRotation(player, flying)

    player.packetStateData.lastPacketWasTeleport = teleported
    player.packetStateData.lastPacketWasServerRotation = serverRotated

    if (teleported || serverRotated) {
      player.tracking.firstTickProcessed = false
    }

    DuplicateFlyingFilter.apply(player, flying, event)

    if (!event.isCancelled) {
      if (!teleported && !serverRotated) {
        processRotation(player, flying)
      }
      BlockContext.update(player)
      if (!teleported && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
        tickEntityInterpolation(player)
      }
    }

    if (!teleported) {
      player.packetStateData.packetPlayerOnGround = flying.isOnGround
    }
  }

  fun syncServerState(event: PacketReceiveEvent, player: ShardPlayer) {
    val state = player.packetStateData
    if (!state.lastPacketWasTeleport && !state.lastPacketWasServerRotation) return
    val flying = WrapperPlayClientPlayerFlying(event)
    val movement = player.movement
    if (flying.hasPositionChanged()) {
      movement.x = flying.location.x
      movement.y = flying.location.y
      movement.z = flying.location.z
      state.duplicatePacketFilterPosition = flying.location.position
    }
    if (flying.hasRotationChanged()) {
      movement.yaw = flying.location.yaw
      movement.pitch = flying.location.pitch
      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
    }
  }

  fun onDataTick(player: ShardPlayer) {
    val tracking = player.tracking
    if (tracking.pendingBufferReset) {
      tracking.pendingBufferReset = false
      player.tickBuffer.resetForSession()
    }
    player.ensureTickBufferCapacity()
    val skipped =
      player.packetStateData.shouldIgnoreFlyingTick && !tracking.windowStartThisTick ||
        !withinFloodBudget(player)
    if (skipped) return

    val buf = player.tickBuffer
    buf.capture(player)
    if (tracking.windowStartThisTick) {
      buf.markAttack()
    }
    collectManager.onTick(player)
    if (!player.detectionDisabled) player.ai.onDataTick()
    buf.advance()
    player.compensatedFireworks.onTickEnd()
  }

  fun resetFlags(player: ShardPlayer) {
    player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = false
    player.packetStateData.lastPacketWasTeleport = false
    player.packetStateData.lastPacketWasServerRotation = false
  }

  private fun withinFloodBudget(player: ShardPlayer): Boolean {
    val tracking = player.tracking
    val within = tracking.floodTryConsume(System.nanoTime()) || tracking.windowStartThisTick
    if (!within) {
      tracking.floodDroppedCaptures++
      if (debugManager.isEnabled(DebugCategory.AI_FLOOD)) {
        debugManager.log(
          DebugCategory.AI_FLOOD,
          "Dropped flooded capture for ${player.player.name} " +
            "(dropped=${tracking.floodDroppedCaptures})",
        )
      }
    }
    return within
  }

  private fun tickEntityInterpolation(player: ShardPlayer) {
    // A tick without a movement packet leaves no trace, so any 1.9+ client can skip one unseen.
    val reliable = player.user.clientVersion.isOlderThan(ClientVersion.V_1_9)
    for (entity in player.compensatedEntities.entityMap.values) {
      entity.onMovement(tickingReliably = reliable)
    }
  }

  private fun processRotation(player: ShardPlayer, packet: WrapperPlayClientPlayerFlying) {
    val ignoreRotation =
      player.packetStateData.lastPacketWasOnePointSeventeenDuplicate &&
        player.packets.ignoreDuplicateRotation
    val movement = player.movement

    if (packet.hasPositionChanged()) {
      movement.x = packet.location.x
      movement.y = packet.location.y
      movement.z = packet.location.z
      if (!player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
        player.packetStateData.duplicatePacketFilterPosition = packet.location.position
      }
    }

    if (packet.hasRotationChanged() && !ignoreRotation) {
      player.tracking.rotationThisTick = true
      val newYaw = packet.location.yaw
      val newPitch = packet.location.pitch

      val update: RotationUpdate = player.rotationUpdate
      update.from.yaw = movement.yaw
      update.from.pitch = movement.pitch
      update.to.yaw = newYaw
      update.to.pitch = newPitch
      update.deltaYaw = newYaw - movement.yaw
      update.deltaPitch = newPitch - movement.pitch

      player.aim.process(update)

      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
      movement.yaw = newYaw
      movement.pitch = newPitch
    }
  }
}
