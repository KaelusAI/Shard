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
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove

internal object ClientInput {
  fun onAnimation(event: PacketReceiveEvent, player: ShardPlayer) {
    if (WrapperPlayClientAnimation(event).hand == InteractionHand.MAIN_HAND) {
      player.tracking.onSwing()
    }
  }

  fun onUseItem(player: ShardPlayer) {
    player.tracking.ticksSinceUseItem = 0
  }

  fun onDigging(event: PacketReceiveEvent, player: ShardPlayer) {
    val tracking = player.tracking
    val dig = WrapperPlayClientPlayerDigging(event)
    if (dig.action == DiggingAction.RELEASE_USE_ITEM) {
      tracking.isUsingItem = false
      tracking.updateActiveItem()
    }
    if (dig.action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
      tracking.swapOffhand()
      InventoryTracking.refreshAttackSpeed(player)
    }
  }

  fun onHeldItem(event: PacketReceiveEvent, player: ShardPlayer) {
    val tracking = player.tracking
    tracking.isUsingItem = false
    tracking.updateActiveItem()
    tracking.cooldownTicks = 0
    tracking.heldSlot = WrapperPlayClientHeldItemChange(event).slot
    tracking.onSlotSwitch()
    InventoryTracking.refreshAttackSpeed(player)
  }

  fun onEntityAction(event: PacketReceiveEvent, player: ShardPlayer) {
    val tracking = player.tracking
    when (WrapperPlayClientEntityAction(event).action) {
      WrapperPlayClientEntityAction.Action.START_SPRINTING -> tracking.sprinting = true
      WrapperPlayClientEntityAction.Action.STOP_SPRINTING -> tracking.sprinting = false
      WrapperPlayClientEntityAction.Action.START_SNEAKING -> tracking.sneaking = true
      WrapperPlayClientEntityAction.Action.STOP_SNEAKING -> tracking.sneaking = false
      WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA -> tracking.gliding = true
      else -> Unit
    }
  }

  fun onAbilities(event: PacketReceiveEvent, player: ShardPlayer) {
    player.tracking.flying = WrapperPlayClientPlayerAbilities(event).isFlying
  }

  fun onInput(event: PacketReceiveEvent, player: ShardPlayer) {
    val tracking = player.tracking
    val input = WrapperPlayClientPlayerInput(event)
    tracking.inputForward = input.isForward
    tracking.inputBackward = input.isBackward
    tracking.inputLeft = input.isLeft
    tracking.inputRight = input.isRight
    tracking.inputJump = input.isJump
    tracking.inputShift = input.isShift
    tracking.inputSprint = input.isSprint
    // Clients below 1.21.2 cannot send this packet, so a proxy wrote the values.
    tracking.inputValid = player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_2)
    if (player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_6)) {
      tracking.sneaking = input.isShift
    }
  }

  fun onSteer(event: PacketReceiveEvent, player: ShardPlayer) {
    val tracking = player.tracking
    val steer = WrapperPlayClientSteerVehicle(event)
    tracking.inputForward = steer.forward > 0f
    tracking.inputBackward = steer.forward < 0f
    tracking.inputLeft = steer.sideways > 0f
    tracking.inputRight = steer.sideways < 0f
    tracking.inputJump = steer.isJump
    tracking.inputShift = steer.isUnmount
    tracking.inputValid = true
  }

  fun onVehicleMove(event: PacketReceiveEvent, player: ShardPlayer) {
    if (!player.tracking.inVehicle) return
    val position = WrapperPlayClientVehicleMove(event).position
    val movement = player.movement
    movement.x = position.x
    movement.y = position.y
    movement.z = position.z
  }
}
