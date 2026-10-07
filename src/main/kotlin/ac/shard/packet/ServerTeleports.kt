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
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation
import java.util.Queue
import kotlin.math.abs

internal object ServerTeleports {
  // 1.7 rounding
  private const val TELEPORT_EPSILON = 1.0E-7
  private const val FULL_CIRCLE_DEGREES = 360f
  private const val MAX_PITCH = 90f

  fun onPositionAndLook(event: PacketSendEvent, player: ShardPlayer) {
    val wrapper = WrapperPlayServerPlayerPositionAndLook(event)
    val transactionId = player.bracketTransaction(event)
    val location = Vector3d(wrapper.x, wrapper.y, wrapper.z)
    player.pendingTeleports.add(
      ShardPlayer.TeleportData(
        location,
        wrapper.yaw,
        wrapper.pitch,
        wrapper.relativeFlags,
        transactionId,
      )
    )
  }

  fun onRotation(event: PacketSendEvent, player: ShardPlayer) {
    val wrapper = WrapperPlayServerPlayerRotation(event)
    val transactionId = player.preTransaction()
    val storedPitch =
      if (wrapper.isRelativePitch) {
        wrapper.pitch
      } else {
        (wrapper.pitch % FULL_CIRCLE_DEGREES).coerceIn(-MAX_PITCH, MAX_PITCH)
      }
    player.pendingRotations.add(
      ShardPlayer.RotationData(
        wrapper.yaw,
        storedPitch,
        wrapper.isRelativeYaw,
        wrapper.isRelativePitch,
        transactionId,
      )
    )
  }

  fun acceptTeleport(player: ShardPlayer, flying: WrapperPlayClientPlayerFlying): Boolean =
    flying.hasPositionChanged() &&
      flying.hasRotationChanged() &&
      accept(player, player.pendingTeleports, { it.transactionId }) { landsOn(player, flying, it) }

  fun acceptRotation(player: ShardPlayer, flying: WrapperPlayClientPlayerFlying): Boolean =
    flying.hasRotationChanged() &&
      !flying.hasPositionChanged() &&
      accept(player, player.pendingRotations, { it.transactionId }) {
        matchesServerRotation(it, flying)
      }

  private fun <T> accept(
    player: ShardPlayer,
    queue: Queue<T>,
    transactionOf: (T) -> Int,
    matches: (T) -> Boolean,
  ): Boolean {
    val received = player.transactions.lastTransactionReceived.get()
    var head = queue.peek()
    while (head != null && received > transactionOf(head) && !matches(head)) {
      queue.poll()
      head = queue.peek()
    }
    val accepted = head != null && received >= transactionOf(head) && matches(head)
    if (accepted) queue.poll()
    return accepted
  }

  private fun landsOn(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
    teleport: ShardPlayer.TeleportData,
  ): Boolean {
    val movement = player.movement
    val flags = teleport.flags
    val at = flying.location
    val expectedX = teleport.location.x + if (flags.has(RelativeFlag.X)) movement.x else 0.0
    val expectedY = teleport.location.y + if (flags.has(RelativeFlag.Y)) movement.y else 0.0
    val expectedZ = teleport.location.z + if (flags.has(RelativeFlag.Z)) movement.z else 0.0
    val threshold = if (teleport.isRelativePos()) player.getMovementThreshold() else 0.0
    return abs(at.x - expectedX) <= threshold &&
      abs(at.y - expectedY) <= TELEPORT_EPSILON + threshold &&
      abs(at.z - expectedZ) <= threshold &&
      teleport.rotationMatches(at.yaw, at.pitch)
  }

  private fun matchesServerRotation(
    rotation: ShardPlayer.RotationData,
    flying: WrapperPlayClientPlayerFlying,
  ): Boolean {
    val yawMatches = rotation.relativeYaw || flying.location.yaw == rotation.yaw
    val pitchMatches = rotation.relativePitch || flying.location.pitch == rotation.pitch
    return yawMatches && pitchMatches
  }
}
