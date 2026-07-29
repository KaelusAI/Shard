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
package ac.shard.utils.nmsutil

import ac.shard.entity.PacketEntity
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes

object BoundingBoxSize {

  fun getWidth(entity: PacketEntity): Float {
    val base = getWidthBase(entity)
    val babyScaled = if (entity.isBaby) base * 0.5f else base
    return babyScaled * entity.scale
  }

  fun getHeight(entity: PacketEntity): Float {
    val base = getHeightBase(entity)
    val babyScaled = if (entity.isBaby) base * 0.5f else base
    return babyScaled * entity.scale
  }

  private fun getWidthBase(entity: PacketEntity): Float {
    val type = entity.type
    return when {
      type == EntityTypes.PLAYER -> 0.6f
      type == EntityTypes.ZOMBIE ||
        type == EntityTypes.ZOMBIE_VILLAGER ||
        type == EntityTypes.HUSK ||
        type == EntityTypes.DROWNED ||
        type == EntityTypes.SKELETON ||
        type == EntityTypes.STRAY ||
        type == EntityTypes.BOGGED ||
        type == EntityTypes.PIGLIN ||
        type == EntityTypes.PIGLIN_BRUTE ||
        type == EntityTypes.ZOMBIFIED_PIGLIN ||
        type == EntityTypes.VILLAGER ||
        type == EntityTypes.WANDERING_TRADER ||
        type == EntityTypes.VINDICATOR ||
        type == EntityTypes.PILLAGER ||
        type == EntityTypes.EVOKER ||
        type == EntityTypes.ILLUSIONER ||
        type == EntityTypes.WITCH ||
        type == EntityTypes.BLAZE ||
        type == EntityTypes.ENDERMAN ||
        type == EntityTypes.BREEZE -> 0.6f
      type == EntityTypes.WITHER_SKELETON -> 0.7f
      type == EntityTypes.SPIDER -> 1.4f
      type == EntityTypes.CAVE_SPIDER -> 0.7f
      type == EntityTypes.CREEPER -> 0.6f
      type == EntityTypes.IRON_GOLEM -> 1.4f
      type == EntityTypes.SNOW_GOLEM -> 0.7f
      type == EntityTypes.RAVAGER -> 1.95f
      type == EntityTypes.GHAST -> 4.0f
      type == EntityTypes.ENDER_DRAGON -> 16.0f
      type == EntityTypes.WITHER -> 0.9f
      type == EntityTypes.WARDEN -> 0.9f
      type == EntityTypes.GUARDIAN -> 0.85f
      type == EntityTypes.ELDER_GUARDIAN -> 1.9975f
      type == EntityTypes.WOLF -> 0.6f
      type == EntityTypes.COW ||
        type == EntityTypes.SHEEP ||
        type == EntityTypes.MOOSHROOM ||
        type == EntityTypes.PIG ||
        type == EntityTypes.GOAT ||
        type == EntityTypes.LLAMA ||
        type == EntityTypes.TRADER_LLAMA ||
        type == EntityTypes.DOLPHIN ||
        type == EntityTypes.STRIDER -> 0.9f
      type == EntityTypes.HORSE ||
        type == EntityTypes.DONKEY ||
        type == EntityTypes.MULE ||
        type == EntityTypes.SKELETON_HORSE ||
        type == EntityTypes.ZOMBIE_HORSE -> 1.3964844f
      type == EntityTypes.HOGLIN || type == EntityTypes.ZOGLIN -> 1.3964844f
      type == EntityTypes.PANDA -> 1.3f
      type == EntityTypes.CHICKEN ||
        type == EntityTypes.ENDERMITE ||
        type == EntityTypes.SILVERFISH ||
        type == EntityTypes.VEX -> 0.4f
      type == EntityTypes.RABBIT -> 0.4f
      type == EntityTypes.BEE -> 0.7f
      type == EntityTypes.BAT ||
        type == EntityTypes.PARROT ||
        type == EntityTypes.COD ||
        type == EntityTypes.TROPICAL_FISH -> 0.5f
      type == EntityTypes.AXOLOTL -> 0.75f
      type == EntityTypes.FROG -> 0.5f
      type == EntityTypes.ARMOR_STAND -> 0.5f
      type == EntityTypes.END_CRYSTAL -> 2.0f
      type == EntityTypes.SLIME || type == EntityTypes.MAGMA_CUBE -> {
        val size = entity.slimeSize
        0.52f * size
      }
      type == EntityTypes.PHANTOM -> 0.9f + entity.phantomSize * 0.2f
      type == EntityTypes.HAPPY_GHAST -> 4.0f
      EntityTypes.isTypeInstanceOf(type, EntityTypes.BOAT) -> 1.375f
      else -> 0.6f
    }
  }

  private fun playerPoseHeight(pose: Int): Float =
    when (pose) {
      EntityPose.SLEEPING.ordinal -> 0.2f
      EntityPose.FALL_FLYING.ordinal,
      EntityPose.SWIMMING.ordinal,
      EntityPose.SPIN_ATTACK.ordinal -> 0.6f
      EntityPose.CROUCHING.ordinal -> 1.5f
      else -> 1.8f
    }

  private fun getHeightBase(entity: PacketEntity): Float {
    val type = entity.type
    return when {
      type == EntityTypes.PLAYER -> playerPoseHeight(entity.pose)
      type == EntityTypes.ZOMBIE ||
        type == EntityTypes.ZOMBIE_VILLAGER ||
        type == EntityTypes.HUSK ||
        type == EntityTypes.DROWNED ||
        type == EntityTypes.SKELETON ||
        type == EntityTypes.STRAY ||
        type == EntityTypes.BOGGED ||
        type == EntityTypes.PIGLIN ||
        type == EntityTypes.PIGLIN_BRUTE ||
        type == EntityTypes.ZOMBIFIED_PIGLIN -> 1.95f
      type == EntityTypes.VILLAGER ||
        type == EntityTypes.WANDERING_TRADER ||
        type == EntityTypes.VINDICATOR ||
        type == EntityTypes.PILLAGER ||
        type == EntityTypes.EVOKER ||
        type == EntityTypes.ILLUSIONER -> 1.95f
      type == EntityTypes.WITCH -> 1.95f
      type == EntityTypes.WITHER_SKELETON -> 2.4f
      type == EntityTypes.ENDERMAN -> 2.9f
      type == EntityTypes.SPIDER -> 0.9f
      type == EntityTypes.CAVE_SPIDER -> 0.5f
      type == EntityTypes.CREEPER -> 1.7f
      type == EntityTypes.BLAZE -> 1.8f
      type == EntityTypes.IRON_GOLEM -> 2.7f
      type == EntityTypes.SNOW_GOLEM -> 1.9f
      type == EntityTypes.RAVAGER -> 2.2f
      type == EntityTypes.GHAST -> 4.0f
      type == EntityTypes.ENDER_DRAGON -> 8.0f
      type == EntityTypes.WITHER -> 3.5f
      type == EntityTypes.WARDEN -> 2.9f
      type == EntityTypes.GUARDIAN -> 0.85f
      type == EntityTypes.ELDER_GUARDIAN -> 1.9975f
      type == EntityTypes.BREEZE -> 1.77f
      type == EntityTypes.WOLF -> 0.85f
      type == EntityTypes.COW || type == EntityTypes.MOOSHROOM -> 1.4f
      type == EntityTypes.SHEEP -> 1.3f
      type == EntityTypes.PIG -> 0.9f
      type == EntityTypes.GOAT -> 1.3f
      type == EntityTypes.LLAMA || type == EntityTypes.TRADER_LLAMA -> 1.875f
      type == EntityTypes.DOLPHIN -> 0.6f
      type == EntityTypes.STRIDER -> 1.7f
      type == EntityTypes.HORSE ||
        type == EntityTypes.DONKEY ||
        type == EntityTypes.MULE ||
        type == EntityTypes.SKELETON_HORSE ||
        type == EntityTypes.ZOMBIE_HORSE -> 1.6f
      type == EntityTypes.HOGLIN || type == EntityTypes.ZOGLIN -> 1.4f
      type == EntityTypes.PANDA -> 1.25f
      type == EntityTypes.CHICKEN -> 0.7f
      type == EntityTypes.ENDERMITE || type == EntityTypes.SILVERFISH -> 0.3f
      type == EntityTypes.VEX -> 0.8f
      type == EntityTypes.RABBIT -> 0.5f
      type == EntityTypes.BEE -> 0.6f
      type == EntityTypes.BAT -> 0.9f
      type == EntityTypes.PARROT -> 0.9f
      type == EntityTypes.COD -> 0.3f
      type == EntityTypes.TROPICAL_FISH -> 0.4f
      type == EntityTypes.AXOLOTL -> 0.42f
      type == EntityTypes.FROG -> 0.55f
      type == EntityTypes.ARMOR_STAND -> 1.975f
      type == EntityTypes.END_CRYSTAL -> 2.0f
      type == EntityTypes.SLIME || type == EntityTypes.MAGMA_CUBE -> {
        val size = entity.slimeSize
        0.52f * size
      }
      type == EntityTypes.PHANTOM -> 0.5f
      type == EntityTypes.HAPPY_GHAST -> 4.5f
      EntityTypes.isTypeInstanceOf(type, EntityTypes.BOAT) -> 0.5625f
      else -> 1.95f
    }
  }
}
