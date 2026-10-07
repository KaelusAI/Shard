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
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk

internal object WorldReplication {
  fun onChunk(event: PacketSendEvent, player: ShardPlayer) {
    val col = WrapperPlayServerChunkData(event).column
    val sections = col.chunks ?: return
    player.compensatedWorld.onChunkLoad(col.x, col.z, sections)
  }

  fun onUnload(event: PacketSendEvent, player: ShardPlayer) {
    val unload = WrapperPlayServerUnloadChunk(event)
    player.compensatedWorld.onChunkUnload(unload.chunkX, unload.chunkZ)
  }

  fun onBlock(event: PacketSendEvent, player: ShardPlayer) {
    val blockChange = WrapperPlayServerBlockChange(event)
    val pos = blockChange.blockPosition
    val state = blockChange.blockState
    player.latencyUtils.addRealTimeTask(player.lastSentTransaction()) {
      player.compensatedWorld.setBlock(pos.x, pos.y, pos.z, state)
    }
  }

  fun onMultiBlock(event: PacketSendEvent, player: ShardPlayer) {
    val multi = WrapperPlayServerMultiBlockChange(event)
    val version = PacketEvents.getAPI().serverManager.version.toClientVersion()
    val changes = multi.blocks.map { Triple(it.x, it.y, it.z) to it.getBlockState(version) }
    player.latencyUtils.addRealTimeTask(player.lastSentTransaction()) {
      for ((at, state) in changes) {
        player.compensatedWorld.setBlock(at.first, at.second, at.third, state)
      }
    }
  }
}
