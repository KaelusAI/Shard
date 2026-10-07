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

import ac.shard.data.TickData
import ac.shard.database.DatabaseManager
import ac.shard.entity.PacketEntity
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement

class ClientActions(
  private val scheduler: SchedulerService,
  private val databaseManager: DatabaseManager,
) {
  fun onInteract(event: PacketReceiveEvent, player: ShardPlayer) {
    val interact = WrapperPlayClientInteractEntity(event)
    if (interact.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
      attack(interact.entityId, player)
    }
  }

  fun onAttack(event: PacketReceiveEvent, player: ShardPlayer) {
    attack(WrapperPlayClientAttack(event).entityId, player)
  }

  fun onPlacement(event: PacketReceiveEvent, player: ShardPlayer) {
    val placement = WrapperPlayClientPlayerBlockPlacement(event)
    val tracking = player.tracking
    tracking.ticksSinceUseItem = 0
    val pos = placement.blockPosition
    val state = player.compensatedWorld.getBlock(pos.x, pos.y, pos.z)
    val blockClass =
      when (state?.type) {
        null -> TickData.PLACE_UNKNOWN
        StateTypes.OBSIDIAN,
        StateTypes.CRYING_OBSIDIAN -> TickData.PLACE_OBSIDIAN
        StateTypes.BEDROCK -> TickData.PLACE_BEDROCK
        StateTypes.RESPAWN_ANCHOR -> TickData.PLACE_RESPAWN_ANCHOR
        else -> TickData.PLACE_OTHER_SOLID
      }
    val cursor = placement.cursorPosition
    tracking.onBlockPlace(
      placement.faceId,
      blockClass.toInt(),
      pos.x,
      pos.y,
      pos.z,
      floatArrayOf(cursor.x, cursor.y, cursor.z),
      placement.insideBlock.orElse(false),
      placement.hand == InteractionHand.OFF_HAND,
    )
    if (state?.type == StateTypes.RESPAWN_ANCHOR) {
      tracking.anchorCharge = state.charges
      tracking.anchorUseInterval =
        player.crystalTracker.anchorUseInterval(pos.x, pos.y, pos.z, tracking.tickIndex)
      tracking.raiseWindowStart(TickData.START_USE_RESPAWN_ANCHOR)
    } else if (blockClass == TickData.PLACE_OBSIDIAN || blockClass == TickData.PLACE_BEDROCK) {
      val held = tracking.hotbarItems.getOrNull(tracking.heldSlot)
      if (held == ItemTypes.END_CRYSTAL) tracking.raiseWindowStart(TickData.START_PLACE_END_CRYSTAL)
    }
  }

  private fun attack(targetId: Int, player: ShardPlayer) {
    if (targetId == player.entityId) return
    val target = player.compensatedEntities.getEntity(targetId)
    val tracking = player.tracking
    tracking.crystalSpawnToAttack =
      player.crystalTracker.spawnToAttack(targetId, tracking.tickIndex)
    tracking.attackTargetType = classify(target)
    if (target != null && target.isPlayer) {
      tracking.onAttack(targetId, tracking.sprinting)
      tracking.raiseWindowStart(TickData.START_MELEE_PLAYER)
      recordFirstAttack(player)
      return
    }
    tracking.raiseWindowStart(
      when {
        target == null -> TickData.START_ATTACK_ENTITY_OTHER
        target.type == EntityTypes.END_CRYSTAL -> TickData.START_ATTACK_END_CRYSTAL
        target.isLivingEntity -> TickData.START_MELEE_LIVING_OTHER
        else -> TickData.START_ATTACK_ENTITY_OTHER
      }
    )
  }

  private fun classify(target: PacketEntity?): Short =
    when {
      target == null -> TickData.TARGET_UNKNOWN
      target.isPlayer -> TickData.TARGET_PLAYER
      target.type == EntityTypes.END_CRYSTAL -> TickData.TARGET_END_CRYSTAL
      target.isLivingEntity -> TickData.TARGET_LIVING_OTHER
      EntityTypes.isTypeInstanceOf(target.type, EntityTypes.BOAT) ||
        EntityTypes.isTypeInstanceOf(target.type, EntityTypes.MINECART) -> TickData.TARGET_VEHICLE
      else -> TickData.TARGET_OTHER
    }

  private fun recordFirstAttack(player: ShardPlayer) {
    if (player.combat.hasAttacked) return
    player.combat.hasAttacked = true
    val attacker = player.uuid
    val now = System.currentTimeMillis()
    scheduler.runAsync { databaseManager.database.recordAttack(attacker, now) }
  }
}
