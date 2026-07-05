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
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.UUID

class CompensatedEntities(private val player: ShardPlayer) {
  val entityMap: Int2ObjectOpenHashMap<PacketEntity> = Int2ObjectOpenHashMap()
  val self: PacketEntitySelf = PacketEntitySelf(player)

  fun addEntity(entityId: Int, uuid: UUID, type: EntityType) {
    entityMap.put(entityId, PacketEntity(player, uuid, type))
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
}
