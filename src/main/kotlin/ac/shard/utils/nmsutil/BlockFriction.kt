/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
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
package ac.shard.utils.nmsutil

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.protocol.world.states.type.StateType
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue

object BlockFriction {
  const val DEFAULT = 0.6f

  fun of(type: StateType?): Float =
    when (type) {
      StateTypes.ICE,
      StateTypes.PACKED_ICE,
      StateTypes.FROSTED_ICE -> 0.98f
      StateTypes.BLUE_ICE -> 0.989f
      StateTypes.SLIME_BLOCK,
      StateTypes.HONEY_BLOCK -> 0.8f
      else -> DEFAULT
    }

  fun isClimbable(type: StateType?): Boolean =
    when (type) {
      StateTypes.LADDER,
      StateTypes.VINE,
      StateTypes.TWISTING_VINES,
      StateTypes.TWISTING_VINES_PLANT,
      StateTypes.WEEPING_VINES,
      StateTypes.WEEPING_VINES_PLANT,
      StateTypes.CAVE_VINES,
      StateTypes.CAVE_VINES_PLANT,
      StateTypes.SCAFFOLDING -> true
      else -> false
    }

  fun stuckMultiplier(type: StateType?): FloatArray? =
    when (type) {
      StateTypes.COBWEB -> floatArrayOf(0.25f, 0.05f, 0.25f)
      StateTypes.SWEET_BERRY_BUSH -> floatArrayOf(0.8f, 0.75f, 0.8f)
      StateTypes.POWDER_SNOW -> floatArrayOf(0.9f, 1.5f, 0.9f)
      else -> null
    }

  fun isSlime(type: StateType?): Boolean = type == StateTypes.SLIME_BLOCK

  fun isHoney(type: StateType?): Boolean = type == StateTypes.HONEY_BLOCK

  fun isSoulSand(type: StateType?): Boolean = type == StateTypes.SOUL_SAND

  fun isMud(type: StateType?): Boolean = type == StateTypes.MUD

  // No waterlogged property on these, but their vanilla fluid state is water.
  private val WATER_HOLDING_BLOCKS =
    setOf(
      StateTypes.KELP,
      StateTypes.KELP_PLANT,
      StateTypes.SEAGRASS,
      StateTypes.TALL_SEAGRASS,
      StateTypes.BUBBLE_COLUMN,
    )

  fun isWater(state: WrappedBlockState?): Boolean {
    if (state == null) return false
    if (state.type == StateTypes.WATER || state.type in WATER_HOLDING_BLOCKS) return true
    return state.hasProperty(StateValue.WATERLOGGED) && state.isWaterlogged
  }
}
