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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.utils.data

import com.github.retrooper.packetevents.util.Vector3d

class PacketStateData {
  var packetPlayerOnGround: Boolean = false
  var lastPacketWasTeleport: Boolean = false
  var lastPacketWasServerRotation: Boolean = false
  var lastPacketWasOnePointSeventeenDuplicate: Boolean = false
  var duplicatePacketFilterPosition: Vector3d = Vector3d(0.0, 0.0, 0.0)

  val shouldIgnoreFlyingTick: Boolean
    get() =
      lastPacketWasOnePointSeventeenDuplicate ||
        lastPacketWasTeleport ||
        lastPacketWasServerRotation
}
