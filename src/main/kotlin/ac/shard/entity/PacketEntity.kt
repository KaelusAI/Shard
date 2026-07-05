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
 * GNU General Public License for more details
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.entity

import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import java.util.UUID

open class PacketEntity(val player: ShardPlayer, val uuid: UUID, val type: EntityType) {
  val isPlayer: Boolean = type == EntityTypes.PLAYER

  @Volatile var riding: PacketEntity? = null

  fun mount(vehicle: PacketEntity) {
    riding = vehicle
  }

  fun eject() {
    riding = null
  }
}
