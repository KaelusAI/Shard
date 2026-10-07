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

import ac.shard.entity.PacketEntity
import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import java.util.Optional

internal object EntityMetadata {
  private const val LIVING_FLAGS_INDEX_MODERN = 8
  private const val LIVING_FLAGS_INDEX_POSE = 7
  private const val LIVING_FLAGS_INDEX_LEGACY = 6
  private const val LIVING_FLAGS_INDEX_ANCIENT = 5
  private const val GLIDING_FLAG = 0x80
  private const val SWIMMING_FLAG = 0x10
  private const val RIPTIDE_FLAG = 0x04
  private const val USE_ITEM_FLAG = 0x01
  private const val OFFHAND_FLAG = 0x02
  private const val FIREWORK_ATTACHED_INDEX_MODERN = 9
  private const val BABY_INDEX_MIN = 15
  private const val BABY_INDEX_MAX = 17
  private const val SIZE_METADATA_MIN_INDEX = 15

  private val AGEABLE_MOBS: List<EntityType> by lazy {
    listOf(
      EntityTypes.ZOMBIE,
      EntityTypes.ZOMBIE_VILLAGER,
      EntityTypes.HUSK,
      EntityTypes.DROWNED,
      EntityTypes.PIGLIN,
      EntityTypes.ZOGLIN,
      EntityTypes.PIG,
      EntityTypes.COW,
      EntityTypes.CHICKEN,
      EntityTypes.SHEEP,
      EntityTypes.VILLAGER,
      EntityTypes.HORSE,
      EntityTypes.DONKEY,
      EntityTypes.MULE,
      EntityTypes.LLAMA,
      EntityTypes.MOOSHROOM,
      EntityTypes.WOLF,
      EntityTypes.CAT,
      EntityTypes.OCELOT,
      EntityTypes.RABBIT,
      EntityTypes.POLAR_BEAR,
      EntityTypes.TURTLE,
      EntityTypes.PANDA,
      EntityTypes.FOX,
      EntityTypes.BEE,
      EntityTypes.HOGLIN,
      EntityTypes.STRIDER,
      EntityTypes.GOAT,
      EntityTypes.AXOLOTL,
      EntityTypes.FROG,
      EntityTypes.CAMEL,
      EntityTypes.SNIFFER,
      EntityTypes.ARMADILLO,
    )
  }

  fun onMetadata(event: PacketSendEvent, player: ShardPlayer) {
    val meta = WrapperPlayServerEntityMetadata(event)
    val entityId = meta.entityId
    val isSelf = entityId == player.entityId
    val metadata = meta.entityMetadata
    val serverVersion = PacketEvents.getAPI().serverManager.version
    val livingFlagsIndex =
      when {
        serverVersion.isNewerThanOrEquals(ServerVersion.V_1_17) -> LIVING_FLAGS_INDEX_MODERN
        serverVersion.isNewerThanOrEquals(ServerVersion.V_1_14) -> LIVING_FLAGS_INDEX_POSE
        serverVersion.isNewerThanOrEquals(ServerVersion.V_1_10) -> LIVING_FLAGS_INDEX_LEGACY
        else -> LIVING_FLAGS_INDEX_ANCIENT
      }

    player.latencyUtils.addRealTimeTask(player.lastSentTransaction()) {
      for (data in metadata) {
        entry(data, isSelf, livingFlagsIndex, entityId, player)
      }
    }
  }

  private fun entry(
    data: EntityData<*>,
    isSelf: Boolean,
    livingFlagsIndex: Int,
    entityId: Int,
    player: ShardPlayer,
  ) {
    when {
      data.index == 0 ->
        if (isSelf && data.value is Byte) {
          val flags = (data.value as Byte).toInt()
          player.tracking.gliding =
            (flags and GLIDING_FLAG) != 0 &&
              player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_9)
          player.tracking.swimming =
            (flags and SWIMMING_FLAG) != 0 &&
              player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_13)
        }
      data.index == livingFlagsIndex ->
        if (isSelf && data.value is Byte) {
          val livingFlags = (data.value as Byte).toInt()
          player.tracking.riptideActive = (livingFlags and RIPTIDE_FLAG) == RIPTIDE_FLAG
          player.tracking.isUsingItem = (livingFlags and USE_ITEM_FLAG) != 0
          player.tracking.usingOffhand = (livingFlags and OFFHAND_FLAG) != 0
          player.tracking.updateActiveItem()
        }
      else ->
        if (!isSelf) {
          val entity = player.compensatedEntities.getEntity(entityId)
          if (entity != null) {
            tracked(entity, data)
            firework(entity, entityId, data, player)
          }
        }
    }
  }

  private fun tracked(entity: PacketEntity, data: EntityData<*>) {
    val value = data.value
    val type = entity.type
    when {
      value is EntityPose && type == EntityTypes.PLAYER -> entity.pose = value.ordinal
      value is Boolean &&
        data.index in BABY_INDEX_MIN..BABY_INDEX_MAX &&
        AGEABLE_MOBS.any { it == type } -> entity.isBaby = value
      value is Int &&
        data.index >= SIZE_METADATA_MIN_INDEX &&
        (type == EntityTypes.SLIME || type == EntityTypes.MAGMA_CUBE) -> entity.slimeSize = value
      value is Int && data.index >= SIZE_METADATA_MIN_INDEX && type == EntityTypes.PHANTOM ->
        entity.phantomSize = value
    }
  }

  private fun firework(
    entity: PacketEntity,
    entityId: Int,
    data: EntityData<*>,
    player: ShardPlayer,
  ) {
    if (entity.type != EntityTypes.FIREWORK_ROCKET) return
    val serverVersion = PacketEvents.getAPI().serverManager.version
    val offset =
      when {
        serverVersion.isOlderThanOrEquals(ServerVersion.V_1_12_2) -> 2
        serverVersion.isOlderThanOrEquals(ServerVersion.V_1_16_5) -> 1
        else -> 0
      }
    if (data.index != FIREWORK_ATTACHED_INDEX_MODERN - offset) return

    val value = data.value
    val attachedId: Int? =
      when (value) {
        is Int -> value
        is Optional<*> -> value.orElse(null) as? Int
        else -> null
      }
    if (attachedId != null && attachedId == player.entityId) {
      player.compensatedFireworks.addNewFirework(entityId)
    }
  }
}
