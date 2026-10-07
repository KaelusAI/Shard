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
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers

internal object EntityMovement {
  private val STILL = Vector3d(0.0, 0.0, 0.0)

  fun onRelativeMove(event: PacketSendEvent, player: ShardPlayer) {
    val move = WrapperPlayServerEntityRelativeMove(event)
    move(player, move.entityId, Vector3d(move.deltaX, move.deltaY, move.deltaZ), relative = true)
  }

  fun onRelativeMoveAndRotation(event: PacketSendEvent, player: ShardPlayer) {
    val move = WrapperPlayServerEntityRelativeMoveAndRotation(event)
    move(player, move.entityId, Vector3d(move.deltaX, move.deltaY, move.deltaZ), relative = true)
  }

  fun onTeleport(event: PacketSendEvent, player: ShardPlayer) {
    val tp = WrapperPlayServerEntityTeleport(event)
    move(player, tp.entityId, tp.position, relative = false)
  }

  fun onRotation(event: PacketSendEvent, player: ShardPlayer) {
    move(
      player,
      WrapperPlayServerEntityRotation(event).entityId,
      STILL,
      relative = true,
      hasPos = false,
    )
  }

  fun onPositionSync(event: PacketSendEvent, player: ShardPlayer) {
    val sync = WrapperPlayServerEntityPositionSync(event)
    move(player, sync.id, sync.values.position, relative = false)
  }

  fun onSetPassengers(event: PacketSendEvent, player: ShardPlayer) {
    val wrapper = WrapperPlayServerSetPassengers(event)
    val vehicleId = wrapper.entityId
    val isPassenger = wrapper.passengers.contains(player.entityId)
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      val self = player.compensatedEntities.self
      val vehicle = player.compensatedEntities.getEntity(vehicleId) ?: return@addRealTimeTask
      if (isPassenger) {
        self.mount(vehicle)
        player.tracking.inVehicle = true
      } else if (self.riding === vehicle) {
        self.eject()
        player.tracking.inVehicle = self.riding != null
      }
    }
  }

  fun onAttach(event: PacketSendEvent, player: ShardPlayer) {
    val wrapper = WrapperPlayServerAttachEntity(event)
    if (wrapper.isLeash || wrapper.attachedId != player.entityId) return
    val holdingId = wrapper.holdingId
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      val self = player.compensatedEntities.self
      if (holdingId != -1) {
        val vehicle = player.compensatedEntities.getEntity(holdingId) ?: return@addRealTimeTask
        self.mount(vehicle)
        player.tracking.inVehicle = true
      } else if (self.riding != null) {
        self.eject()
        player.tracking.inVehicle = self.riding != null
      }
    }
  }

  private fun move(
    player: ShardPlayer,
    entityId: Int,
    delta: Vector3d,
    relative: Boolean,
    hasPos: Boolean = true,
  ) {
    val entity = player.compensatedEntities.getEntity(entityId)
    if (entity != null) {
      if (entity.lastTransactionHung == player.lastSentTransaction()) {
        player.sendTransaction()
      }
      entity.lastTransactionHung = player.lastSentTransaction()
    }

    val lastTrans = player.lastSentTransaction()
    player.latencyUtils.addRealTimeTask(lastTrans) {
      val ent = player.compensatedEntities.getEntity(entityId) ?: return@addRealTimeTask
      ent.onFirstTransaction(relative, hasPos, delta.x, delta.y, delta.z, player)
    }

    player.latencyUtils.addRealTimeTask(lastTrans + 1) {
      val ent = player.compensatedEntities.getEntity(entityId) ?: return@addRealTimeTask
      ent.onSecondTransaction()
    }
  }
}
