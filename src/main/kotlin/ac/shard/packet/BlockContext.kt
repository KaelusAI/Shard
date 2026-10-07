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
import ac.shard.player.state.TrackingState
import ac.shard.utils.nmsutil.BlockFriction
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import kotlin.math.abs
import kotlin.math.floor

internal object BlockContext {
  private const val GROUND_SEARCH_OFFSET = 0.5000001
  private const val BODY_OFFSET = 0.5
  private const val HEAD_OFFSET = 1.6

  fun update(player: ShardPlayer) {
    val tracking = player.tracking
    val m = player.movement
    val world = player.compensatedWorld

    val footX = floor(m.x).toInt()
    val footZ = floor(m.z).toInt()
    val frictionY = floor(m.y - GROUND_SEARCH_OFFSET).toInt()
    val feetY = floor(m.y).toInt()
    val bodyY = floor(m.y + BODY_OFFSET).toInt()
    val headY = floor(m.y + HEAD_OFFSET).toInt()

    val frictionBlock = world.getBlock(footX, frictionY, footZ)
    if (frictionBlock != null) {
      val type = frictionBlock.type
      tracking.groundFriction = BlockFriction.of(type)
      tracking.onSlime = BlockFriction.isSlime(type)
      tracking.onHoney = BlockFriction.isHoney(type)
      tracking.onSoulSand = BlockFriction.isSoulSand(type)
      tracking.onMud = BlockFriction.isMud(type)
    } else {
      tracking.onSlime = false
      tracking.onHoney = false
      tracking.onSoulSand = false
      tracking.onMud = false
    }
    world.getBlock(footX, feetY, footZ)?.let { state ->
      tracking.isClimbing = BlockFriction.isClimbable(state.type)
      tracking.inWater = BlockFriction.isWater(state)
    }
    tracking.pose = pose(tracking)

    var stuckX = 1.0f
    var stuckY = 1.0f
    var stuckZ = 1.0f
    for (y in intArrayOf(feetY, bodyY, headY)) {
      val stuck =
        world.getBlock(footX, y, footZ)?.let { BlockFriction.stuckMultiplier(it.type) } ?: continue
      stuckX = mostStuck(stuckX, stuck[0])
      stuckY = mostStuck(stuckY, stuck[1])
      stuckZ = mostStuck(stuckZ, stuck[2])
    }
    tracking.stuckMultX = stuckX
    tracking.stuckMultY = stuckY
    tracking.stuckMultZ = stuckZ
  }

  private fun mostStuck(current: Float, candidate: Float): Float =
    if (abs(candidate - 1f) > abs(current - 1f)) candidate else current

  private fun pose(tracking: TrackingState): Int =
    when {
      tracking.gliding -> EntityPose.FALL_FLYING.ordinal
      tracking.swimming -> EntityPose.SWIMMING.ordinal
      tracking.riptideActive -> EntityPose.SPIN_ATTACK.ordinal
      tracking.sneaking -> EntityPose.CROUCHING.ordinal
      else -> EntityPose.STANDING.ordinal
    }
}
