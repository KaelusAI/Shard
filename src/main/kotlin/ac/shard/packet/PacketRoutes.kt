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
package ac.shard.packet

import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import java.util.EnumMap

internal typealias ReceiveHandler = (PacketReceiveEvent, ShardPlayer) -> Unit

internal typealias SendHandler = (PacketSendEvent, ShardPlayer) -> Unit

internal fun receiveRoutes(
  actions: ClientActions
): List<Pair<PacketType.Play.Client, ReceiveHandler>> =
  listOf(
    PacketType.Play.Client.INTERACT_ENTITY to actions::onInteract,
    PacketType.Play.Client.ATTACK to actions::onAttack,
    PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT to actions::onPlacement,
    PacketType.Play.Client.ANIMATION to ClientInput::onAnimation,
    PacketType.Play.Client.USE_ITEM to { _, player -> ClientInput.onUseItem(player) },
    PacketType.Play.Client.PLAYER_DIGGING to ClientInput::onDigging,
    PacketType.Play.Client.HELD_ITEM_CHANGE to ClientInput::onHeldItem,
    PacketType.Play.Client.ENTITY_ACTION to ClientInput::onEntityAction,
    PacketType.Play.Client.PLAYER_ABILITIES to ClientInput::onAbilities,
    PacketType.Play.Client.PLAYER_INPUT to ClientInput::onInput,
    PacketType.Play.Client.STEER_VEHICLE to ClientInput::onSteer,
    PacketType.Play.Client.VEHICLE_MOVE to ClientInput::onVehicleMove,
  )

internal val SEND_ROUTES: List<Pair<PacketType.Play.Server, SendHandler>> =
  listOf(
    PacketType.Play.Server.WINDOW_CONFIRMATION to TransactionAcks::onConfirmationSent,
    PacketType.Play.Server.PING to TransactionAcks::onPingSent,
    PacketType.Play.Server.ENTITY_VELOCITY to SelfStateTracking::onVelocity,
    PacketType.Play.Server.EXPLOSION to SelfStateTracking::onExplosion,
    PacketType.Play.Server.ENTITY_EFFECT to SelfStateTracking::onEffect,
    PacketType.Play.Server.REMOVE_ENTITY_EFFECT to SelfStateTracking::onRemoveEffect,
    PacketType.Play.Server.PLAYER_ABILITIES to SelfStateTracking::onAbilities,
    PacketType.Play.Server.UPDATE_HEALTH to SelfStateTracking::onHealth,
    PacketType.Play.Server.CHANGE_GAME_STATE to SelfStateTracking::onGameState,
    PacketType.Play.Server.JOIN_GAME to SelfStateTracking::onJoinGame,
    PacketType.Play.Server.RESPAWN to SelfStateTracking::onRespawn,
    PacketType.Play.Server.SPAWN_ENTITY to EntityTracking::onSpawnEntity,
    PacketType.Play.Server.SPAWN_LIVING_ENTITY to EntityTracking::onSpawnLiving,
    PacketType.Play.Server.SPAWN_PAINTING to EntityTracking::onSpawnPainting,
    PacketType.Play.Server.SPAWN_PLAYER to EntityTracking::onSpawnPlayer,
    PacketType.Play.Server.DESTROY_ENTITIES to EntityTracking::onDestroy,
    PacketType.Play.Server.ENTITY_STATUS to EntityTracking::onStatus,
    PacketType.Play.Server.UPDATE_ATTRIBUTES to EntityTracking::onAttributes,
    PacketType.Play.Server.ENTITY_METADATA to EntityMetadata::onMetadata,
    PacketType.Play.Server.ENTITY_RELATIVE_MOVE to EntityMovement::onRelativeMove,
    PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION to
      EntityMovement::onRelativeMoveAndRotation,
    PacketType.Play.Server.ENTITY_TELEPORT to EntityMovement::onTeleport,
    PacketType.Play.Server.ENTITY_ROTATION to EntityMovement::onRotation,
    PacketType.Play.Server.ENTITY_POSITION_SYNC to EntityMovement::onPositionSync,
    PacketType.Play.Server.SET_PASSENGERS to EntityMovement::onSetPassengers,
    PacketType.Play.Server.ATTACH_ENTITY to EntityMovement::onAttach,
    PacketType.Play.Server.CHUNK_DATA to WorldReplication::onChunk,
    PacketType.Play.Server.UNLOAD_CHUNK to WorldReplication::onUnload,
    PacketType.Play.Server.BLOCK_CHANGE to WorldReplication::onBlock,
    PacketType.Play.Server.MULTI_BLOCK_CHANGE to WorldReplication::onMultiBlock,
    PacketType.Play.Server.SET_SLOT to InventoryTracking::onSetSlot,
    PacketType.Play.Server.WINDOW_ITEMS to InventoryTracking::onWindowItems,
    PacketType.Play.Server.PLAYER_POSITION_AND_LOOK to ServerTeleports::onPositionAndLook,
    PacketType.Play.Server.PLAYER_ROTATION to ServerTeleports::onRotation,
  )

internal inline fun <reified K : Enum<K>, V> routeTable(routes: List<Pair<K, V>>): Map<K, V> =
  EnumMap<K, V>(K::class.java).apply { putAll(routes) }
