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
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
import java.util.UUID

internal object EntityTracking {
  private const val DEATH_ANIMATION_STATUS = 3

  fun onSpawnEntity(event: PacketSendEvent, player: ShardPlayer) {
    val spawn = WrapperPlayServerSpawnEntity(event)
    val entityId = spawn.entityId
    if (entityId < 0) return
    val uuid = spawn.uuid.orElse(UUID(0, entityId.toLong()))
    val type = spawn.entityType
    val pos = spawn.position
    player.latencyUtils.addRealTimeTask(
      player.spawnTransaction(entityId),
      Runnable {
        player.compensatedEntities.addEntity(entityId, uuid, type, pos.x, pos.y, pos.z)
        if (type == EntityTypes.END_CRYSTAL) {
          player.crystalTracker.onCrystalSpawn(entityId, player.tracking.tickIndex)
        }
      },
    )
  }

  fun onSpawnLiving(event: PacketSendEvent, player: ShardPlayer) {
    val spawn = WrapperPlayServerSpawnLivingEntity(event)
    val entityId = spawn.entityId
    val uuid = spawn.entityUUID
    val type = spawn.entityType
    val pos = spawn.position
    player.latencyUtils.addRealTimeTask(
      player.spawnTransaction(entityId),
      Runnable { player.compensatedEntities.addEntity(entityId, uuid, type, pos.x, pos.y, pos.z) },
    )
  }

  fun onSpawnPainting(event: PacketSendEvent, player: ShardPlayer) {
    val spawn = WrapperPlayServerSpawnPainting(event)
    val entityId = spawn.entityId
    val uuid = spawn.uuid
    player.latencyUtils.addRealTimeTask(
      player.spawnTransaction(entityId),
      Runnable { player.compensatedEntities.addEntity(entityId, uuid, EntityTypes.PAINTING) },
    )
  }

  fun onSpawnPlayer(event: PacketSendEvent, player: ShardPlayer) {
    val spawn = WrapperPlayServerSpawnPlayer(event)
    val entityId = spawn.entityId
    val uuid = spawn.uuid
    val pos = spawn.position
    player.latencyUtils.addRealTimeTask(
      player.spawnTransaction(entityId),
      Runnable {
        player.compensatedEntities.addEntity(
          entityId,
          uuid,
          EntityTypes.PLAYER,
          pos.x,
          pos.y,
          pos.z,
        )
      },
    )
  }

  fun onDestroy(event: PacketSendEvent, player: ShardPlayer) {
    val entityIds = WrapperPlayServerDestroyEntities(event).entityIds
    for (id in entityIds) {
      player.transactions.entitiesDespawnedThisTransaction.add(id)
    }
    val destroyTransaction = player.lastSentTransaction()
    player.latencyUtils.addRealTimeTask(destroyTransaction) {
      val self = player.compensatedEntities.self
      for (id in entityIds) {
        player.crystalTracker.onEntityRemoved(id)
        val entity = player.compensatedEntities.getEntity(id) ?: continue
        entity.isDead = true
        if (self.riding === entity) {
          self.eject()
        }
      }
    }
    player.latencyUtils.addRealTimeTask(
      destroyTransaction + 1,
      Runnable {
        for (id in entityIds) {
          player.compensatedEntities.removeEntity(id)
          player.compensatedFireworks.removeFirework(id)
        }
      },
    )
  }

  fun onStatus(event: PacketSendEvent, player: ShardPlayer) {
    val status = WrapperPlayServerEntityStatus(event)
    if (status.status != DEATH_ANIMATION_STATUS) return
    val entityId = status.entityId
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      player.compensatedEntities.getEntity(entityId)?.isDead = true
    }
  }

  fun onAttributes(event: PacketSendEvent, player: ShardPlayer) {
    val attrs = WrapperPlayServerUpdateAttributes(event)
    val entityId = attrs.entityId
    val properties = attrs.properties
    val id =
      if (entityId == player.entityId) player.bracketTransaction(event)
      else player.lastSentTransaction()
    player.latencyUtils.addRealTimeTask(id) {
      player.compensatedEntities.updateAttributes(entityId, properties)
    }
  }
}
