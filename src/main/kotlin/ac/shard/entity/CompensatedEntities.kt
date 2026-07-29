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

import ac.shard.entity.types.PacketEntitySelf
import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.UUID

class CompensatedEntities(private val player: ShardPlayer) {
  val entityMap: Int2ObjectOpenHashMap<PacketEntity> = Int2ObjectOpenHashMap()
  val self: PacketEntitySelf = PacketEntitySelf(player)

  fun addEntity(
    entityId: Int,
    uuid: UUID,
    type: EntityType,
    x: Double = 0.0,
    y: Double = 0.0,
    z: Double = 0.0,
  ) {
    val packetEntity = PacketEntity(player, uuid, type)
    packetEntity.trackedServerPosition.pos =
      if (player.user.clientVersion.isOlderThan(ClientVersion.V_1_9)) {
        Vector3d(
          (x * LEGACY_SPAWN_SCALE).toInt() / LEGACY_SPAWN_SCALE,
          (y * LEGACY_SPAWN_SCALE).toInt() / LEGACY_SPAWN_SCALE,
          (z * LEGACY_SPAWN_SCALE).toInt() / LEGACY_SPAWN_SCALE,
        )
      } else {
        Vector3d(x, y, z)
      }
    entityMap.put(entityId, packetEntity)
  }

  fun getEntity(entityId: Int): PacketEntity? {
    if (entityId == player.entityId) {
      return self
    }
    return entityMap.get(entityId)
  }

  fun removeEntity(entityId: Int) {
    entityMap.remove(entityId)
  }

  fun clear() {
    self.eject()
    entityMap.clear()
  }

  fun updateAttributes(
    entityId: Int,
    properties: List<WrapperPlayServerUpdateAttributes.Property>,
  ) {
    val entity = getEntity(entityId) ?: return
    val isSelf = entityId == player.entityId
    val tracking = player.tracking
    for (prop in properties) {
      val key = prop.key ?: continue
      when {
        key.endsWith(":scale", ignoreCase = true) ||
          key.equals("minecraft:generic.scale", ignoreCase = true) -> {
          val scale = computeAttribute(prop).toFloat()
          entity.scale = scale
          if (isSelf) tracking.scale = scale
        }
        !isSelf -> Unit
        key.contains("movement_speed", ignoreCase = true) -> {
          tracking.movementSpeed = computeMovementSpeed(prop).toFloat()
        }
        key.contains("entity_interaction_range", ignoreCase = true) -> {
          tracking.entityInteractionRange = computeAttribute(prop).toFloat()
        }
        key.contains("gravity", ignoreCase = true) -> {
          tracking.gravity = computeAttribute(prop).toFloat()
        }
        key.contains("jump_strength", ignoreCase = true) -> {
          tracking.jumpStrength = computeAttribute(prop).toFloat()
        }
        key.contains("step_height", ignoreCase = true) -> {
          tracking.stepHeight = computeAttribute(prop).toFloat()
        }
        key.contains("water_movement_efficiency", ignoreCase = true) -> {
          tracking.waterMovementEfficiency = computeAttribute(prop).toFloat()
        }
      }
    }
  }

  private fun computeMovementSpeed(prop: WrapperPlayServerUpdateAttributes.Property): Double {
    prop.removeModifierIf { mod ->
      mod.getUUID() == SPRINTING_MODIFIER_UUID ||
        mod.name.key.equals("sprinting", ignoreCase = true)
    }
    return computeAttribute(prop)
  }

  private fun computeAttribute(prop: WrapperPlayServerUpdateAttributes.Property): Double =
    prop.calcValue()

  private companion object {
    const val LEGACY_SPAWN_SCALE = 32.0
    val SPRINTING_MODIFIER_UUID: UUID = UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d")
  }
}
