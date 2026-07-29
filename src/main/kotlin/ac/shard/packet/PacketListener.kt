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

import ac.shard.data.CollectManager
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.entity.PacketEntity
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.player.TransactionStamp
import ac.shard.player.state.TrackingState
import ac.shard.utils.nmsutil.BlockFriction
import ac.shard.utils.update.RotationUpdate
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.type.ItemType
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.protocol.potion.PotionType
import com.github.retrooper.packetevents.protocol.potion.PotionTypes
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import java.util.Optional
import java.util.UUID
import org.bukkit.entity.Player

private const val FULL_CIRCLE_DEGREES = 360f
private const val MAX_PITCH = 90f

class PacketListener(
  private val playerDataManager: PlayerDataManager,
  private val collectManager: CollectManager,
  private val debugManager: DebugManager,
) : PacketListenerAbstract() {
  override fun onUserLogin(event: UserLoginEvent) {
    val user: com.github.retrooper.packetevents.protocol.player.User? = event.user
    if (user == null) {
      return
    }
    val player: Player? = event.getPlayer()
    if (player == null) {
      return
    }
    playerDataManager.handleUserLogin(user, player)
  }

  override fun onUserDisconnect(event: UserDisconnectEvent) {
    playerDataManager.handleUserDisconnect(event.user)
  }

  private fun checkTeleportQueue(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
  ): Boolean {
    if (
      !flying.hasPositionChanged() ||
        !flying.hasRotationChanged() ||
        player.pendingTeleports.isEmpty()
    ) {
      return false
    }

    val movement = player.movement
    while (player.pendingTeleports.isNotEmpty()) {
      val teleport = player.pendingTeleports.peek() ?: break
      val lastTransaction = player.transactions.lastTransactionReceived.get()
      if (lastTransaction < teleport.transactionId) {
        return false
      }

      val flyingLocation = flying.location
      val flags = teleport.flags

      val expectedX =
        if (flags.has(RelativeFlag.X)) {
          movement.x + teleport.location.x
        } else {
          teleport.location.x
        }
      val expectedY =
        if (flags.has(RelativeFlag.Y)) {
          movement.y + teleport.location.y
        } else {
          teleport.location.y
        }
      val expectedZ =
        if (flags.has(RelativeFlag.Z)) {
          movement.z + teleport.location.z
        } else {
          teleport.location.z
        }

      val threshold = if (teleport.isRelativePos()) player.getMovementThreshold() else 0.0
      val positionMatches =
        kotlin.math.abs(flyingLocation.x - expectedX) <= threshold &&
          kotlin.math.abs(flyingLocation.y - expectedY) <= TELEPORT_EPSILON + threshold &&
          kotlin.math.abs(flyingLocation.z - expectedZ) <= threshold
      if (positionMatches && teleport.rotationMatches(flyingLocation.yaw, flyingLocation.pitch)) {
        player.pendingTeleports.poll()
        return true
      }

      if (lastTransaction > teleport.transactionId) {
        player.pendingTeleports.poll()
        continue
      }
      return false
    }
    return false
  }

  private fun checkRotationQueue(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
  ): Boolean {
    if (
      !flying.hasRotationChanged() ||
        flying.hasPositionChanged() ||
        player.pendingRotations.isEmpty()
    ) {
      return false
    }

    while (player.pendingRotations.isNotEmpty()) {
      val rotation = player.pendingRotations.peek() ?: break
      val lastTransaction = player.transactions.lastTransactionReceived.get()
      if (lastTransaction < rotation.transactionId) {
        return false
      }

      if (matchesServerRotation(rotation, flying)) {
        player.pendingRotations.poll()
        return true
      }

      if (lastTransaction > rotation.transactionId) {
        player.pendingRotations.poll()
        continue
      }
      return false
    }
    return false
  }

  override fun onPacketReceive(event: PacketReceiveEvent) {
    val player: Player? = event.getPlayer<Player>()
    if (player == null) {
      return
    }

    val shardPlayer = playerDataManager.getPlayer(player) ?: return

    if (handleTransaction(event, shardPlayer)) {
      dropWrapperUnlessRewritten(event)
      return
    }

    val isFlying = WrapperPlayClientPlayerFlying.isFlying(event.packetType)

    if (isFlying) {
      shardPlayer.tracking.onTickStart()
    }

    handleShardClientPackets(event, shardPlayer)

    if (isFlying) {
      handleFlying(event, shardPlayer)
    }

    if (event.isCancelled) {
      if (isFlying) {
        shardPlayer.tracking.onTickAborted()
      }
      resetFlags(shardPlayer)
      return
    }

    if (
      shardPlayer.packetStateData.lastPacketWasTeleport ||
        shardPlayer.packetStateData.lastPacketWasServerRotation
    ) {
      updatePlayerState(shardPlayer, WrapperPlayClientPlayerFlying(event))
    }

    if (isFlying) {
      onDataTick(shardPlayer)
    }

    shardPlayer.checkManager.onPacketReceive(event)

    if (isFlying) {
      shardPlayer.tracking.onTickEnd()
    }

    resetFlags(shardPlayer)
    dropWrapperUnlessRewritten(event)
  }

  private fun dropWrapperUnlessRewritten(event: ProtocolPacketEvent) {
    if (!event.needsReEncode()) {
      event.setLastUsedWrapper(null)
    }
  }

  private fun handleTransaction(event: PacketReceiveEvent, shardPlayer: ShardPlayer): Boolean {
    if (event.packetType == PacketType.Play.Client.WINDOW_CONFIRMATION) {
      val transaction = WrapperPlayClientWindowConfirmation(event)
      val id = transaction.actionId
      if (id <= 0 && addTransactionResponse(shardPlayer, id)) {
        event.isCancelled = true
      }
      return true
    } else if (event.packetType == PacketType.Play.Client.PONG) {
      val id = WrapperPlayClientPong(event).id
      // a PONG id wider than a short is not ours
      if (id == id.toShort().toInt() && addTransactionResponse(shardPlayer, id.toShort())) {
        event.isCancelled = true
      }
      return true
    }
    return false
  }

  private fun matchesServerRotation(
    rotation: ShardPlayer.RotationData,
    flying: WrapperPlayClientPlayerFlying,
  ): Boolean {
    val yawMatches = rotation.relativeYaw || flying.location.yaw == rotation.yaw
    val pitchMatches = rotation.relativePitch || flying.location.pitch == rotation.pitch
    return yawMatches && pitchMatches
  }

  private fun handleFlying(event: PacketReceiveEvent, shardPlayer: ShardPlayer) {
    val flying = WrapperPlayClientPlayerFlying(event)

    val teleported = checkTeleportQueue(shardPlayer, flying)
    val serverRotated = !teleported && checkRotationQueue(shardPlayer, flying)

    shardPlayer.packetStateData.lastPacketWasTeleport = teleported
    shardPlayer.packetStateData.lastPacketWasServerRotation = serverRotated

    if (teleported || serverRotated) {
      shardPlayer.tracking.firstTickProcessed = false
    }

    isMojangStupid(shardPlayer, flying, event)

    if (!event.isCancelled) {
      if (!teleported && !serverRotated) {
        processRotation(shardPlayer, flying)
      }
      updateBlockContext(shardPlayer)
      if (!teleported && !shardPlayer.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
        tickEntityInterpolation(shardPlayer)
      }
    }

    if (!teleported) {
      shardPlayer.packetStateData.packetPlayerOnGround = flying.isOnGround
    }
  }

  private fun tickEntityInterpolation(shardPlayer: ShardPlayer) {
    // A tick without a movement packet leaves no trace, so any 1.9+ client can skip one unseen.
    val reliable = shardPlayer.user.clientVersion.isOlderThan(ClientVersion.V_1_9)
    for (entity in shardPlayer.compensatedEntities.entityMap.values) {
      entity.onMovement(tickingReliably = reliable)
    }
  }

  private fun onDataTick(shardPlayer: ShardPlayer) {
    if (shardPlayer.tracking.pendingBufferReset) {
      shardPlayer.tracking.pendingBufferReset = false
      shardPlayer.tickBuffer.resetForSession()
    }
    shardPlayer.ensureTickBufferCapacity()
    if (
      shardPlayer.packetStateData.shouldIgnoreFlyingTick && !shardPlayer.tracking.attackThisTick
    ) {
      return
    }

    val withinFloodBudget = shardPlayer.tracking.floodTryConsume(System.nanoTime())
    if (!withinFloodBudget && !shardPlayer.tracking.attackThisTick) {
      shardPlayer.tracking.floodDroppedCaptures++
      if (debugManager.isEnabled(DebugCategory.AI_FLOOD)) {
        debugManager.log(
          DebugCategory.AI_FLOOD,
          "Dropped flooded capture for ${shardPlayer.player.name} " +
            "(dropped=${shardPlayer.tracking.floodDroppedCaptures})",
        )
      }
      return
    }

    val buf = shardPlayer.tickBuffer
    buf.capture(shardPlayer)
    if (shardPlayer.tracking.attackThisTick) {
      buf.markAttack()
    }
    collectManager.onTick(shardPlayer)
    shardPlayer.checkManager.onDataTick(shardPlayer)
    buf.advance()
    shardPlayer.compensatedFireworks.onTickEnd()
  }

  private fun processRotation(shardPlayer: ShardPlayer, packet: WrapperPlayClientPlayerFlying) {
    val ignoreRotation =
      shardPlayer.packetStateData.lastPacketWasOnePointSeventeenDuplicate &&
        shardPlayer.isIgnoreDuplicatePacketRotation()
    val movement = shardPlayer.movement

    if (packet.hasPositionChanged()) {
      movement.x = packet.location.x
      movement.y = packet.location.y
      movement.z = packet.location.z
      if (!shardPlayer.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
        shardPlayer.packetStateData.duplicatePacketFilterPosition = packet.location.position
      }
    }

    if (packet.hasRotationChanged() && !ignoreRotation) {
      shardPlayer.tracking.rotationThisTick = true
      val newYaw = packet.location.yaw
      val newPitch = packet.location.pitch
      val deltaYaw = newYaw - movement.yaw
      val deltaPitch = newPitch - movement.pitch

      val update: RotationUpdate = shardPlayer.rotationUpdate

      update.from.yaw = movement.yaw
      update.from.pitch = movement.pitch
      update.to.yaw = newYaw
      update.to.pitch = newPitch
      update.deltaYaw = deltaYaw
      update.deltaPitch = deltaPitch

      shardPlayer.checkManager.onRotationUpdate(update)

      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
      movement.yaw = newYaw
      movement.pitch = newPitch
    }
  }

  private fun updatePlayerState(shardPlayer: ShardPlayer, flying: WrapperPlayClientPlayerFlying) {
    val movement = shardPlayer.movement
    if (flying.hasPositionChanged()) {
      movement.x = flying.location.x
      movement.y = flying.location.y
      movement.z = flying.location.z
      shardPlayer.packetStateData.duplicatePacketFilterPosition = flying.location.position
    }
    if (flying.hasRotationChanged()) {
      movement.yaw = flying.location.yaw
      movement.pitch = flying.location.pitch
      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
    }
  }

  private fun resetFlags(shardPlayer: ShardPlayer) {
    shardPlayer.packetStateData.lastPacketWasOnePointSeventeenDuplicate = false
    shardPlayer.packetStateData.lastPacketWasTeleport = false
    shardPlayer.packetStateData.lastPacketWasServerRotation = false
  }

  override fun onPacketSend(event: PacketSendEvent) {
    val player: Player? = event.getPlayer<Player>()
    if (player != null) {
      val shardPlayer = playerDataManager.getPlayer(player) ?: return
      handleShardServerPackets(event, shardPlayer)
      (event.packetType as? PacketType.Play.Server)?.let { packetType ->
        when (packetType) {
          PacketType.Play.Server.WINDOW_CONFIRMATION ->
            handleWindowConfirmation(WrapperPlayServerWindowConfirmation(event), shardPlayer)
          PacketType.Play.Server.PING -> handlePing(WrapperPlayServerPing(event), shardPlayer)
          PacketType.Play.Server.SPAWN_ENTITY ->
            handleSpawnEntity(WrapperPlayServerSpawnEntity(event), shardPlayer)
          PacketType.Play.Server.SPAWN_LIVING_ENTITY ->
            handleSpawnLivingEntity(WrapperPlayServerSpawnLivingEntity(event), shardPlayer)
          PacketType.Play.Server.SPAWN_PAINTING ->
            handleSpawnPainting(WrapperPlayServerSpawnPainting(event), shardPlayer)
          PacketType.Play.Server.SPAWN_PLAYER ->
            handleSpawnPlayer(WrapperPlayServerSpawnPlayer(event), shardPlayer)
          PacketType.Play.Server.SET_PASSENGERS ->
            handleSetPassengers(event, WrapperPlayServerSetPassengers(event), shardPlayer)
          PacketType.Play.Server.ATTACH_ENTITY ->
            handleAttachEntity(event, WrapperPlayServerAttachEntity(event), shardPlayer)
          PacketType.Play.Server.SET_SLOT ->
            handleSetSlot(WrapperPlayServerSetSlot(event), shardPlayer)
          PacketType.Play.Server.WINDOW_ITEMS ->
            handleWindowItems(WrapperPlayServerWindowItems(event), shardPlayer)
          PacketType.Play.Server.DESTROY_ENTITIES ->
            handleDestroyEntities(WrapperPlayServerDestroyEntities(event), shardPlayer)
          PacketType.Play.Server.JOIN_GAME ->
            handleJoinGame(event, WrapperPlayServerJoinGame(event), shardPlayer)
          PacketType.Play.Server.CHANGE_GAME_STATE ->
            handleChangeGameState(WrapperPlayServerChangeGameState(event), shardPlayer)
          PacketType.Play.Server.RESPAWN ->
            handleRespawn(event, WrapperPlayServerRespawn(event), shardPlayer)
          PacketType.Play.Server.PLAYER_POSITION_AND_LOOK ->
            handlePositionAndLook(event, WrapperPlayServerPlayerPositionAndLook(event), shardPlayer)
          PacketType.Play.Server.PLAYER_ROTATION ->
            handlePlayerRotation(WrapperPlayServerPlayerRotation(event), shardPlayer)
          PacketType.Play.Server.ENTITY_RELATIVE_MOVE -> {
            val move = WrapperPlayServerEntityRelativeMove(event)
            handleMoveEntity(
              shardPlayer,
              move.entityId,
              move.deltaX,
              move.deltaY,
              move.deltaZ,
              relative = true,
              hasPos = true,
            )
          }
          PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION -> {
            val move = WrapperPlayServerEntityRelativeMoveAndRotation(event)
            handleMoveEntity(
              shardPlayer,
              move.entityId,
              move.deltaX,
              move.deltaY,
              move.deltaZ,
              relative = true,
              hasPos = true,
            )
          }
          PacketType.Play.Server.ENTITY_TELEPORT -> {
            val tp = WrapperPlayServerEntityTeleport(event)
            handleMoveEntity(
              shardPlayer,
              tp.entityId,
              tp.position.x,
              tp.position.y,
              tp.position.z,
              relative = false,
              hasPos = true,
            )
          }
          PacketType.Play.Server.ENTITY_ROTATION -> {
            val rotation = WrapperPlayServerEntityRotation(event)
            handleMoveEntity(
              shardPlayer,
              rotation.entityId,
              0.0,
              0.0,
              0.0,
              relative = true,
              hasPos = false,
            )
          }
          PacketType.Play.Server.ENTITY_POSITION_SYNC -> {
            val sync = WrapperPlayServerEntityPositionSync(event)
            val pos = sync.values.position
            handleMoveEntity(
              shardPlayer,
              sync.id,
              pos.x,
              pos.y,
              pos.z,
              relative = false,
              hasPos = true,
            )
          }
          else -> Unit
        }
      }
      dropWrapperUnlessRewritten(event)
    }
  }

  private fun handleShardClientPackets(event: PacketReceiveEvent, shardPlayer: ShardPlayer) {
    val tracking = shardPlayer.tracking

    when (event.packetType) {
      PacketType.Play.Client.INTERACT_ENTITY -> {
        val interact = WrapperPlayClientInteractEntity(event)
        if (interact.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
          handleAttack(interact.entityId, shardPlayer)
        }
      }
      PacketType.Play.Client.ATTACK -> {
        handleAttack(WrapperPlayClientAttack(event).entityId, shardPlayer)
      }
      PacketType.Play.Client.ANIMATION -> {
        if (WrapperPlayClientAnimation(event).hand == InteractionHand.MAIN_HAND) {
          tracking.onSwing()
        }
      }
      PacketType.Play.Client.USE_ITEM,
      PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT -> {
        tracking.ticksSinceUseItem = 0
      }
      PacketType.Play.Client.PLAYER_DIGGING -> {
        val dig = WrapperPlayClientPlayerDigging(event)
        if (dig.action == DiggingAction.RELEASE_USE_ITEM) {
          tracking.isUsingItem = false
          tracking.updateActiveItem()
        }
      }
      PacketType.Play.Client.HELD_ITEM_CHANGE -> {
        tracking.isUsingItem = false
        tracking.updateActiveItem()
        tracking.cooldownTicks = 0
        tracking.heldSlot = WrapperPlayClientHeldItemChange(event).slot
        updateHeldAttackSpeed(shardPlayer)
      }
      PacketType.Play.Client.ENTITY_ACTION -> {
        val action = WrapperPlayClientEntityAction(event)
        when (action.action) {
          WrapperPlayClientEntityAction.Action.START_SPRINTING -> {
            tracking.sprinting = true
          }
          WrapperPlayClientEntityAction.Action.STOP_SPRINTING -> {
            tracking.sprinting = false
          }
          WrapperPlayClientEntityAction.Action.START_SNEAKING -> tracking.sneaking = true
          WrapperPlayClientEntityAction.Action.STOP_SNEAKING -> tracking.sneaking = false
          WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA -> tracking.gliding = true
          else -> Unit
        }
      }
      PacketType.Play.Client.PLAYER_ABILITIES -> {
        tracking.flying = WrapperPlayClientPlayerAbilities(event).isFlying
      }
      PacketType.Play.Client.PLAYER_INPUT -> {
        val input = WrapperPlayClientPlayerInput(event)
        tracking.inputForward = input.isForward
        tracking.inputBackward = input.isBackward
        tracking.inputLeft = input.isLeft
        tracking.inputRight = input.isRight
        tracking.inputJump = input.isJump
        tracking.inputShift = input.isShift
        tracking.inputSprint = input.isSprint
        // Clients below 1.21.2 cannot send this packet, so a proxy wrote the values.
        tracking.inputValid =
          shardPlayer.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_2)
        if (shardPlayer.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_6)) {
          tracking.sneaking = input.isShift
        }
      }
      PacketType.Play.Client.STEER_VEHICLE -> {
        val steer = WrapperPlayClientSteerVehicle(event)
        tracking.inputForward = steer.forward > 0f
        tracking.inputBackward = steer.forward < 0f
        tracking.inputLeft = steer.sideways > 0f
        tracking.inputRight = steer.sideways < 0f
        tracking.inputJump = steer.isJump
        tracking.inputShift = steer.isUnmount
        tracking.inputValid = true
      }
      PacketType.Play.Client.VEHICLE_MOVE -> {
        if (tracking.inVehicle) {
          val position = WrapperPlayClientVehicleMove(event).position
          val movement = shardPlayer.movement
          movement.x = position.x
          movement.y = position.y
          movement.z = position.z
        }
      }
      else -> Unit
    }
  }

  private fun handleAttack(targetId: Int, shardPlayer: ShardPlayer) {
    if (targetId == shardPlayer.entityId) return
    val target = shardPlayer.compensatedEntities.getEntity(targetId)
    if (target != null && target.isPlayer) {
      shardPlayer.tracking.onAttack(targetId, shardPlayer.tracking.sprinting)
    }
  }

  private fun handleShardServerPackets(event: PacketSendEvent, shardPlayer: ShardPlayer) {
    val tracking = shardPlayer.tracking

    when (event.packetType) {
      PacketType.Play.Server.ENTITY_VELOCITY -> {
        val vel = WrapperPlayServerEntityVelocity(event)
        if (vel.entityId == shardPlayer.entityId) {
          val velocity = vel.velocity
          shardPlayer.sendTransaction()
          shardPlayer.latencyUtils.addRealTimeTask(
            shardPlayer.transactions.lastTransactionSent.get()
          ) {
            tracking.onKnockback(velocity.x, velocity.y, velocity.z)
          }
          event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
        }
      }
      PacketType.Play.Server.EXPLOSION -> {
        val explosion = WrapperPlayServerExplosion(event)
        val kb = explosion.playerMotion
        if (kb != null && (kb.x != 0f || kb.y != 0f || kb.z != 0f)) {
          shardPlayer.sendTransaction()
          shardPlayer.latencyUtils.addRealTimeTask(
            shardPlayer.transactions.lastTransactionSent.get()
          ) {
            tracking.onExplosion()
          }
          event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
        }
      }
      PacketType.Play.Server.ENTITY_EFFECT -> {
        val effect = WrapperPlayServerEntityEffect(event)
        if (effect.entityId == shardPlayer.entityId) {
          val potionType = effect.potionType
          val amplifier = effect.effectAmplifier
          shardPlayer.sendTransaction()
          shardPlayer.latencyUtils.addRealTimeTask(
            shardPlayer.transactions.lastTransactionSent.get()
          ) {
            handlePotionEffect(tracking, potionType, amplifier)
          }
          event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
        }
      }
      PacketType.Play.Server.REMOVE_ENTITY_EFFECT -> {
        val remove = WrapperPlayServerRemoveEntityEffect(event)
        if (remove.entityId == shardPlayer.entityId) {
          val potionType = remove.potionType
          shardPlayer.sendTransaction()
          shardPlayer.latencyUtils.addRealTimeTask(
            shardPlayer.transactions.lastTransactionSent.get()
          ) {
            handlePotionRemove(tracking, potionType)
          }
          event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
        }
      }
      PacketType.Play.Server.UPDATE_ATTRIBUTES -> {
        val attrs = WrapperPlayServerUpdateAttributes(event)
        val entityId = attrs.entityId
        val properties = attrs.properties
        val isSelf = entityId == shardPlayer.entityId
        if (isSelf) shardPlayer.sendTransaction()
        shardPlayer.latencyUtils.addRealTimeTask(
          shardPlayer.transactions.lastTransactionSent.get()
        ) {
          shardPlayer.compensatedEntities.updateAttributes(entityId, properties)
        }
        if (isSelf) event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
      }
      PacketType.Play.Server.PLAYER_ABILITIES -> {
        val abilities = WrapperPlayServerPlayerAbilities(event)
        val isFlying = abilities.isFlying
        shardPlayer.sendTransaction()
        shardPlayer.latencyUtils.addRealTimeTask(
          shardPlayer.transactions.lastTransactionSent.get()
        ) {
          tracking.flying = isFlying
        }
        event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
      }
      PacketType.Play.Server.UPDATE_HEALTH -> {
        val update = WrapperPlayServerUpdateHealth(event)
        val food = update.food
        val newHealth = update.health
        shardPlayer.sendTransaction()
        shardPlayer.latencyUtils.addRealTimeTask(
          shardPlayer.transactions.lastTransactionSent.get()
        ) {
          val lost = tracking.health - newHealth
          if (lost > 0f) {
            tracking.damageTakenThisTick = lost
            tracking.ticksSinceDamage = 0
          }
          tracking.health = newHealth
          tracking.foodLevel = food
        }
        event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
      }
      PacketType.Play.Server.ENTITY_METADATA -> {
        handleEntityMetadata(WrapperPlayServerEntityMetadata(event), shardPlayer)
      }
      PacketType.Play.Server.ENTITY_STATUS -> {
        val status = WrapperPlayServerEntityStatus(event)
        if (status.status == DEATH_ANIMATION_STATUS) {
          val entityId = status.entityId
          shardPlayer.sendTransaction()
          shardPlayer.latencyUtils.addRealTimeTask(
            shardPlayer.transactions.lastTransactionSent.get()
          ) {
            shardPlayer.compensatedEntities.getEntity(entityId)?.isDead = true
          }
          event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
        }
      }
      PacketType.Play.Server.CHUNK_DATA -> {
        handleChunkData(WrapperPlayServerChunkData(event), shardPlayer)
      }
      PacketType.Play.Server.BLOCK_CHANGE -> {
        handleBlockChange(WrapperPlayServerBlockChange(event), shardPlayer)
      }
      PacketType.Play.Server.MULTI_BLOCK_CHANGE -> {
        handleMultiBlockChange(WrapperPlayServerMultiBlockChange(event), shardPlayer)
      }
      PacketType.Play.Server.UNLOAD_CHUNK -> {
        val unload = WrapperPlayServerUnloadChunk(event)
        shardPlayer.compensatedWorld.onChunkUnload(unload.chunkX, unload.chunkZ)
      }
      else -> Unit
    }
  }

  private fun handlePotionEffect(tracking: TrackingState, potionType: PotionType?, amplifier: Int) {
    if (potionType == null) return
    when (potionType) {
      PotionTypes.JUMP_BOOST -> tracking.jumpAmplifier = amplifier
      PotionTypes.SLOW_FALLING -> tracking.slowFalling = true
      PotionTypes.BLINDNESS -> tracking.hasBlindness = true
      PotionTypes.SPEED -> tracking.speedAmplifier = amplifier
      PotionTypes.SLOWNESS -> tracking.slownessAmplifier = amplifier
      PotionTypes.HASTE -> tracking.hasteAmplifier = amplifier
      PotionTypes.MINING_FATIGUE -> tracking.miningFatigueAmplifier = amplifier
      PotionTypes.LEVITATION -> tracking.levitationAmplifier = amplifier
      PotionTypes.DOLPHINS_GRACE -> tracking.dolphinsGrace = true
      else -> Unit
    }
  }

  private fun handlePotionRemove(tracking: TrackingState, potionType: PotionType?) {
    if (potionType == null) return
    when (potionType) {
      PotionTypes.JUMP_BOOST -> tracking.jumpAmplifier = -1
      PotionTypes.SLOW_FALLING -> tracking.slowFalling = false
      PotionTypes.BLINDNESS -> tracking.hasBlindness = false
      PotionTypes.SPEED -> tracking.speedAmplifier = -1
      PotionTypes.SLOWNESS -> tracking.slownessAmplifier = -1
      PotionTypes.HASTE -> tracking.hasteAmplifier = -1
      PotionTypes.MINING_FATIGUE -> tracking.miningFatigueAmplifier = -1
      PotionTypes.LEVITATION -> tracking.levitationAmplifier = -1
      PotionTypes.DOLPHINS_GRACE -> tracking.dolphinsGrace = false
      else -> Unit
    }
  }

  private fun handleEntityMetadata(
    meta: WrapperPlayServerEntityMetadata,
    shardPlayer: ShardPlayer,
  ) {
    val entityId = meta.entityId
    val isSelf = entityId == shardPlayer.entityId
    val metadata = meta.entityMetadata
    val serverVersion = PacketEvents.getAPI().serverManager.version
    val livingFlagsIndex =
      when {
        serverVersion.isNewerThanOrEquals(ServerVersion.V_1_17) -> LIVING_FLAGS_INDEX_MODERN
        serverVersion.isNewerThanOrEquals(ServerVersion.V_1_14) -> LIVING_FLAGS_INDEX_POSE
        serverVersion.isNewerThanOrEquals(ServerVersion.V_1_10) -> LIVING_FLAGS_INDEX_LEGACY
        else -> LIVING_FLAGS_INDEX_ANCIENT
      }

    shardPlayer.latencyUtils.addRealTimeTask(shardPlayer.transactions.lastTransactionSent.get()) {
      for (data in metadata) {
        handleEntityMetadataEntry(data, isSelf, livingFlagsIndex, entityId, shardPlayer)
      }
    }
  }

  private fun handleEntityMetadataEntry(
    data: EntityData<*>,
    isSelf: Boolean,
    livingFlagsIndex: Int,
    entityId: Int,
    shardPlayer: ShardPlayer,
  ) {
    when {
      data.index == 0 ->
        if (isSelf && data.value is Byte) {
          val flags = (data.value as Byte).toInt()
          shardPlayer.tracking.gliding =
            (flags and GLIDING_FLAG) != 0 &&
              shardPlayer.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_9)
          shardPlayer.tracking.swimming =
            (flags and SWIMMING_FLAG) != 0 &&
              shardPlayer.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_13)
        }
      data.index == livingFlagsIndex ->
        if (isSelf && data.value is Byte) {
          val livingFlags = (data.value as Byte).toInt()
          shardPlayer.tracking.riptideActive = (livingFlags and RIPTIDE_FLAG) == RIPTIDE_FLAG
          shardPlayer.tracking.isUsingItem = (livingFlags and USE_ITEM_FLAG) != 0
          shardPlayer.tracking.usingOffhand = (livingFlags and OFFHAND_FLAG) != 0
          shardPlayer.tracking.updateActiveItem()
        }
      else ->
        if (!isSelf) {
          val entity = shardPlayer.compensatedEntities.getEntity(entityId)
          if (entity != null) {
            handleTrackedEntityMetadata(entity, data)
            handleFireworkMetadata(entity, entityId, data, shardPlayer)
          }
        }
    }
  }

  private fun handleTrackedEntityMetadata(entity: PacketEntity, data: EntityData<*>) {
    val value = data.value
    val type = entity.type
    when {
      value is EntityPose && type == EntityTypes.PLAYER -> entity.pose = value.ordinal
      value is Boolean && data.index in AGEABLE_BABY_INDEX_RANGE && isAgeableMob(type) ->
        entity.isBaby = value
      value is Int &&
        data.index >= SIZE_METADATA_MIN_INDEX &&
        (type == EntityTypes.SLIME || type == EntityTypes.MAGMA_CUBE) -> entity.slimeSize = value
      value is Int && data.index >= SIZE_METADATA_MIN_INDEX && type == EntityTypes.PHANTOM ->
        entity.phantomSize = value
    }
  }

  private fun handleFireworkMetadata(
    entity: PacketEntity,
    entityId: Int,
    data: EntityData<*>,
    shardPlayer: ShardPlayer,
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
    if (attachedId != null && attachedId == shardPlayer.entityId) {
      shardPlayer.compensatedFireworks.addNewFirework(entityId)
    }
  }

  private fun isAgeableMob(type: EntityType): Boolean {
    return type == EntityTypes.ZOMBIE ||
      type == EntityTypes.ZOMBIE_VILLAGER ||
      type == EntityTypes.HUSK ||
      type == EntityTypes.DROWNED ||
      type == EntityTypes.PIGLIN ||
      type == EntityTypes.ZOGLIN ||
      type == EntityTypes.PIG ||
      type == EntityTypes.COW ||
      type == EntityTypes.CHICKEN ||
      type == EntityTypes.SHEEP ||
      type == EntityTypes.VILLAGER ||
      type == EntityTypes.HORSE ||
      type == EntityTypes.DONKEY ||
      type == EntityTypes.MULE ||
      type == EntityTypes.LLAMA ||
      type == EntityTypes.MOOSHROOM ||
      type == EntityTypes.WOLF ||
      type == EntityTypes.CAT ||
      type == EntityTypes.OCELOT ||
      type == EntityTypes.RABBIT ||
      type == EntityTypes.POLAR_BEAR ||
      type == EntityTypes.TURTLE ||
      type == EntityTypes.PANDA ||
      type == EntityTypes.FOX ||
      type == EntityTypes.BEE ||
      type == EntityTypes.HOGLIN ||
      type == EntityTypes.STRIDER ||
      type == EntityTypes.GOAT ||
      type == EntityTypes.AXOLOTL ||
      type == EntityTypes.FROG ||
      type == EntityTypes.CAMEL ||
      type == EntityTypes.SNIFFER ||
      type == EntityTypes.ARMADILLO
  }

  private fun handleChunkData(chunk: WrapperPlayServerChunkData, shardPlayer: ShardPlayer) {
    val col = chunk.column
    val sections = col.chunks ?: return
    shardPlayer.compensatedWorld.onChunkLoad(col.x, col.z, sections)
  }

  private fun handleBlockChange(
    blockChange: WrapperPlayServerBlockChange,
    shardPlayer: ShardPlayer,
  ) {
    val pos = blockChange.blockPosition
    val state = blockChange.blockState
    shardPlayer.latencyUtils.addRealTimeTask(shardPlayer.transactions.lastTransactionSent.get()) {
      shardPlayer.compensatedWorld.setBlock(pos.x, pos.y, pos.z, state)
    }
  }

  private fun handleMultiBlockChange(
    multi: WrapperPlayServerMultiBlockChange,
    shardPlayer: ShardPlayer,
  ) {
    val version = shardPlayer.user.clientVersion
    val changes = multi.blocks.map { Triple(it.x, it.y, it.z) to it.getBlockState(version) }
    shardPlayer.latencyUtils.addRealTimeTask(shardPlayer.transactions.lastTransactionSent.get()) {
      for ((at, state) in changes) {
        shardPlayer.compensatedWorld.setBlock(at.first, at.second, at.third, state)
      }
    }
  }

  @Suppress("MagicNumber")
  private fun itemAttackSpeed(item: ItemType?): Float =
    when (item) {
      ItemTypes.WOODEN_SWORD,
      ItemTypes.STONE_SWORD,
      ItemTypes.IRON_SWORD,
      ItemTypes.GOLDEN_SWORD,
      ItemTypes.DIAMOND_SWORD,
      ItemTypes.NETHERITE_SWORD -> 1.6f
      ItemTypes.WOODEN_AXE,
      ItemTypes.STONE_AXE -> 0.8f
      ItemTypes.IRON_AXE -> 0.9f
      ItemTypes.GOLDEN_AXE,
      ItemTypes.DIAMOND_AXE,
      ItemTypes.NETHERITE_AXE -> 1.0f
      ItemTypes.WOODEN_PICKAXE,
      ItemTypes.STONE_PICKAXE,
      ItemTypes.IRON_PICKAXE,
      ItemTypes.GOLDEN_PICKAXE,
      ItemTypes.DIAMOND_PICKAXE,
      ItemTypes.NETHERITE_PICKAXE -> 1.2f
      ItemTypes.WOODEN_SHOVEL,
      ItemTypes.STONE_SHOVEL,
      ItemTypes.IRON_SHOVEL,
      ItemTypes.GOLDEN_SHOVEL,
      ItemTypes.DIAMOND_SHOVEL,
      ItemTypes.NETHERITE_SHOVEL -> 1.0f
      ItemTypes.TRIDENT -> 1.1f
      else -> DEFAULT_ATTACK_SPEED
    }

  private fun updateHeldAttackSpeed(shardPlayer: ShardPlayer) {
    val tracking = shardPlayer.tracking
    tracking.attackSpeed = itemAttackSpeed(tracking.hotbarItems.getOrNull(tracking.heldSlot))
  }

  private fun handleSetSlot(wrapper: WrapperPlayServerSetSlot, shardPlayer: ShardPlayer) {
    if (wrapper.windowId != 0) return
    if (wrapper.slot == OFFHAND_SLOT) {
      shardPlayer.tracking.offhandItem = wrapper.item.type
      shardPlayer.tracking.updateActiveItem()
      return
    }
    val hotbarIndex = wrapper.slot - HOTBAR_START_SLOT
    if (hotbarIndex in 0 until TrackingState.HOTBAR_SIZE) {
      shardPlayer.tracking.hotbarItems[hotbarIndex] = wrapper.item.type
      shardPlayer.tracking.updateActiveItem()
      updateHeldAttackSpeed(shardPlayer)
    }
  }

  private fun handleWindowItems(wrapper: WrapperPlayServerWindowItems, shardPlayer: ShardPlayer) {
    if (wrapper.windowId != 0) return
    val items = wrapper.items
    val tracking = shardPlayer.tracking
    for (i in 0 until TrackingState.HOTBAR_SIZE) {
      val slot = HOTBAR_START_SLOT + i
      if (slot < items.size) tracking.hotbarItems[i] = items[slot].type
    }
    if (OFFHAND_SLOT < items.size) tracking.offhandItem = items[OFFHAND_SLOT].type
    tracking.updateActiveItem()
    updateHeldAttackSpeed(shardPlayer)
  }

  private fun mostStuck(current: Float, candidate: Float): Float =
    if (kotlin.math.abs(candidate - 1f) > kotlin.math.abs(current - 1f)) candidate else current

  private fun computePose(tracking: TrackingState): Int =
    when {
      tracking.gliding -> EntityPose.FALL_FLYING.ordinal
      tracking.swimming -> EntityPose.SWIMMING.ordinal
      tracking.riptideActive -> EntityPose.SPIN_ATTACK.ordinal
      tracking.sneaking -> EntityPose.CROUCHING.ordinal
      else -> EntityPose.STANDING.ordinal
    }

  private fun handleChangeGameState(
    gs: WrapperPlayServerChangeGameState,
    shardPlayer: ShardPlayer,
  ) {
    if (gs.reason == WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE) {
      shardPlayer.tracking.gameMode = gs.value.toInt()
    }
  }

  private fun updateBlockContext(shardPlayer: ShardPlayer) {
    val tracking = shardPlayer.tracking
    val m = shardPlayer.movement
    val world = shardPlayer.compensatedWorld

    val footX = kotlin.math.floor(m.x).toInt()
    val footZ = kotlin.math.floor(m.z).toInt()
    val frictionY = kotlin.math.floor(m.y - GROUND_SEARCH_OFFSET).toInt()
    val feetY = kotlin.math.floor(m.y).toInt()
    val bodyY = kotlin.math.floor(m.y + BODY_OFFSET).toInt()
    val headY = kotlin.math.floor(m.y + HEAD_OFFSET).toInt()

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
    tracking.pose = computePose(tracking)

    var stuckX = 1.0f
    var stuckY = 1.0f
    var stuckZ = 1.0f
    for (y in intArrayOf(feetY, bodyY, headY)) {
      val state = world.getBlock(footX, y, footZ) ?: continue
      val stuck = BlockFriction.stuckMultiplier(state.type) ?: continue
      stuckX = mostStuck(stuckX, stuck[0])
      stuckY = mostStuck(stuckY, stuck[1])
      stuckZ = mostStuck(stuckZ, stuck[2])
    }
    tracking.stuckMultX = stuckX
    tracking.stuckMultY = stuckY
    tracking.stuckMultZ = stuckZ
  }

  @Suppress("LongParameterList")
  private fun handleMoveEntity(
    shardPlayer: ShardPlayer,
    entityId: Int,
    deltaX: Double,
    deltaY: Double,
    deltaZ: Double,
    relative: Boolean,
    hasPos: Boolean,
  ) {
    val entity = shardPlayer.compensatedEntities.getEntity(entityId)
    if (entity != null) {
      if (entity.lastTransactionHung == shardPlayer.transactions.lastTransactionSent.get()) {
        shardPlayer.sendTransaction()
      }
      entity.lastTransactionHung = shardPlayer.transactions.lastTransactionSent.get()
    }

    val lastTrans = shardPlayer.transactions.lastTransactionSent.get()
    shardPlayer.latencyUtils.addRealTimeTask(lastTrans) {
      val ent = shardPlayer.compensatedEntities.getEntity(entityId) ?: return@addRealTimeTask
      ent.onFirstTransaction(relative, hasPos, deltaX, deltaY, deltaZ, shardPlayer)
    }

    shardPlayer.latencyUtils.addRealTimeTask(lastTrans + 1) {
      val ent = shardPlayer.compensatedEntities.getEntity(entityId) ?: return@addRealTimeTask
      ent.onSecondTransaction()
    }
  }

  private fun handleWindowConfirmation(
    confirmation: WrapperPlayServerWindowConfirmation,
    shardPlayer: ShardPlayer,
  ) {
    val id = confirmation.actionId
    val transactions = shardPlayer.transactions
    if (id <= 0 && transactions.didWeSendThatTrans.remove(id)) {
      transactions.entitiesDespawnedThisTransaction.clear()
      transactions.transactionsSent.add(TransactionStamp(id, System.nanoTime()))
      transactions.lastTransactionSent.getAndIncrement()
    }
  }

  private fun handlePing(ping: WrapperPlayServerPing, shardPlayer: ShardPlayer) {
    val id = ping.id
    val transactions = shardPlayer.transactions
    if (id == id.toShort().toInt() && transactions.didWeSendThatTrans.remove(id.toShort())) {
      transactions.entitiesDespawnedThisTransaction.clear()
      transactions.transactionsSent.add(TransactionStamp(id.toShort(), System.nanoTime()))
      transactions.lastTransactionSent.getAndIncrement()
    }
  }

  private fun handleSpawnEntity(spawn: WrapperPlayServerSpawnEntity, shardPlayer: ShardPlayer) {
    if (shardPlayer.transactions.entitiesDespawnedThisTransaction.contains(spawn.entityId)) {
      shardPlayer.sendTransaction()
    }
    val entityId = spawn.entityId
    val uuid = spawn.uuid.orElse(UUID(0, entityId.toLong()))
    val type = spawn.entityType
    val pos = spawn.position
    shardPlayer.latencyUtils.addRealTimeTask(
      shardPlayer.transactions.lastTransactionSent.get(),
      Runnable {
        shardPlayer.compensatedEntities.addEntity(entityId, uuid, type, pos.x, pos.y, pos.z)
      },
    )
  }

  private fun handleSpawnLivingEntity(
    spawn: WrapperPlayServerSpawnLivingEntity,
    shardPlayer: ShardPlayer,
  ) {
    if (shardPlayer.transactions.entitiesDespawnedThisTransaction.contains(spawn.entityId)) {
      shardPlayer.sendTransaction()
    }
    val entityId = spawn.entityId
    val uuid = spawn.entityUUID
    val type = spawn.entityType
    val pos = spawn.position
    shardPlayer.latencyUtils.addRealTimeTask(
      shardPlayer.transactions.lastTransactionSent.get(),
      Runnable {
        shardPlayer.compensatedEntities.addEntity(entityId, uuid, type, pos.x, pos.y, pos.z)
      },
    )
  }

  private fun handleSpawnPainting(spawn: WrapperPlayServerSpawnPainting, shardPlayer: ShardPlayer) {
    if (shardPlayer.transactions.entitiesDespawnedThisTransaction.contains(spawn.entityId)) {
      shardPlayer.sendTransaction()
    }
    val entityId = spawn.entityId
    val uuid = spawn.uuid
    shardPlayer.latencyUtils.addRealTimeTask(
      shardPlayer.transactions.lastTransactionSent.get(),
      Runnable { shardPlayer.compensatedEntities.addEntity(entityId, uuid, EntityTypes.PAINTING) },
    )
  }

  private fun handleSpawnPlayer(spawn: WrapperPlayServerSpawnPlayer, shardPlayer: ShardPlayer) {
    if (shardPlayer.transactions.entitiesDespawnedThisTransaction.contains(spawn.entityId)) {
      shardPlayer.sendTransaction()
    }
    val entityId = spawn.entityId
    val uuid = spawn.uuid
    val pos = spawn.position
    shardPlayer.latencyUtils.addRealTimeTask(
      shardPlayer.transactions.lastTransactionSent.get(),
      Runnable {
        shardPlayer.compensatedEntities.addEntity(
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

  private fun handleSetPassengers(
    event: PacketSendEvent,
    wrapper: WrapperPlayServerSetPassengers,
    shardPlayer: ShardPlayer,
  ) {
    val vehicleId = wrapper.entityId
    val isPassenger = wrapper.passengers.contains(shardPlayer.entityId)

    shardPlayer.sendTransaction()
    shardPlayer.latencyUtils.addRealTimeTask(shardPlayer.transactions.lastTransactionSent.get()) {
      val self = shardPlayer.compensatedEntities.self
      val vehicle = shardPlayer.compensatedEntities.getEntity(vehicleId) ?: return@addRealTimeTask
      if (isPassenger) {
        self.mount(vehicle)
        shardPlayer.tracking.inVehicle = true
      } else if (self.riding === vehicle) {
        self.eject()
        shardPlayer.tracking.inVehicle = self.riding != null
      }
    }
    event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
  }

  private fun handleAttachEntity(
    event: PacketSendEvent,
    wrapper: WrapperPlayServerAttachEntity,
    shardPlayer: ShardPlayer,
  ) {
    if (wrapper.isLeash || wrapper.attachedId != shardPlayer.entityId) return
    val holdingId = wrapper.holdingId
    shardPlayer.sendTransaction()
    shardPlayer.latencyUtils.addRealTimeTask(shardPlayer.transactions.lastTransactionSent.get()) {
      val self = shardPlayer.compensatedEntities.self
      if (holdingId != -1) {
        val vehicle = shardPlayer.compensatedEntities.getEntity(holdingId) ?: return@addRealTimeTask
        self.mount(vehicle)
        shardPlayer.tracking.inVehicle = true
      } else if (self.riding != null) {
        self.eject()
        shardPlayer.tracking.inVehicle = self.riding != null
      }
    }
    event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
  }

  private fun handleDestroyEntities(
    destroy: WrapperPlayServerDestroyEntities,
    shardPlayer: ShardPlayer,
  ) {
    val entityIds = destroy.entityIds
    for (id in entityIds) {
      shardPlayer.transactions.entitiesDespawnedThisTransaction.add(id)
    }
    val destroyTransaction = shardPlayer.transactions.lastTransactionSent.get()
    shardPlayer.latencyUtils.addRealTimeTask(destroyTransaction) {
      val self = shardPlayer.compensatedEntities.self
      for (id in entityIds) {
        val entity = shardPlayer.compensatedEntities.getEntity(id) ?: continue
        entity.isDead = true
        if (self.riding === entity) {
          self.eject()
        }
      }
    }
    shardPlayer.latencyUtils.addRealTimeTask(
      destroyTransaction + 1,
      Runnable {
        for (id in entityIds) {
          shardPlayer.compensatedEntities.removeEntity(id)
          shardPlayer.compensatedFireworks.removeFirework(id)
        }
      },
    )
  }

  private fun handleJoinGame(
    event: PacketSendEvent,
    join: WrapperPlayServerJoinGame,
    shardPlayer: ShardPlayer,
  ) {
    shardPlayer.tracking.onSequenceBreak()
    val entityId = join.entityId
    val gameMode = join.gameMode
    val dimensionMinY = runCatching { join.dimensionType.getMinY(shardPlayer.user.clientVersion) }
    shardPlayer.entityId = entityId
    shardPlayer.gameMode = gameMode
    shardPlayer.tracking.gameMode = gameMode.ordinal
    shardPlayer.sendTransaction()
    shardPlayer.latencyUtils.addRealTimeTask(
      shardPlayer.transactions.lastTransactionSent.get(),
      Runnable {
        shardPlayer.compensatedEntities.clear()
        shardPlayer.compensatedWorld.clear()
        dimensionMinY.onSuccess { shardPlayer.compensatedWorld.updateMinHeight(it) }
      },
    )
    event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
  }

  private fun handleRespawn(
    event: PacketSendEvent,
    respawn: WrapperPlayServerRespawn,
    shardPlayer: ShardPlayer,
  ) {
    shardPlayer.tracking.onSequenceBreak()
    shardPlayer.gameMode = respawn.gameMode
    shardPlayer.tracking.gameMode = respawn.gameMode.ordinal
    val dimensionMinY = runCatching {
      respawn.dimensionType.getMinY(shardPlayer.user.clientVersion)
    }
    shardPlayer.sendTransaction()
    shardPlayer.latencyUtils.addRealTimeTask(
      shardPlayer.transactions.lastTransactionSent.get(),
      Runnable {
        shardPlayer.compensatedEntities.clear()
        shardPlayer.compensatedWorld.clear()
        dimensionMinY.onSuccess { shardPlayer.compensatedWorld.updateMinHeight(it) }
      },
    )
    event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
  }

  private fun handlePositionAndLook(
    event: PacketSendEvent,
    wrapper: WrapperPlayServerPlayerPositionAndLook,
    shardPlayer: ShardPlayer,
  ) {
    shardPlayer.sendTransaction()
    event.tasksAfterSend.add(Runnable { shardPlayer.sendTransaction() })
    val transactionId = shardPlayer.transactions.lastTransactionSent.get()
    val location = Vector3d(wrapper.x, wrapper.y, wrapper.z)
    val flags = wrapper.relativeFlags
    shardPlayer.pendingTeleports.add(
      ShardPlayer.TeleportData(location, wrapper.yaw, wrapper.pitch, flags, transactionId)
    )
  }

  private fun handlePlayerRotation(
    wrapper: WrapperPlayServerPlayerRotation,
    shardPlayer: ShardPlayer,
  ) {
    shardPlayer.sendTransaction()
    val transactionId = shardPlayer.transactions.lastTransactionSent.get()
    val storedPitch =
      if (wrapper.isRelativePitch) {
        wrapper.pitch
      } else {
        (wrapper.pitch % FULL_CIRCLE_DEGREES).coerceIn(-MAX_PITCH, MAX_PITCH)
      }
    shardPlayer.pendingRotations.add(
      ShardPlayer.RotationData(
        wrapper.yaw,
        storedPitch,
        wrapper.isRelativeYaw,
        wrapper.isRelativePitch,
        transactionId,
      )
    )
  }

  private fun addTransactionResponse(player: ShardPlayer, id: Short): Boolean {
    var data: TransactionStamp? = null
    var hasId = false

    for (entry in player.transactions.transactionsSent) {
      if (entry.id == id) {
        hasId = true
        break
      }
    }

    if (hasId) {
      do {
        data = player.transactions.transactionsSent.poll()
        if (data == null) break
        player.transactions.lastTransactionReceived.incrementAndGet()
      } while (data.id != id)

      player.latencyUtils.handleNettySyncTransaction(
        player.transactions.lastTransactionReceived.get()
      )
      player.transactions.lastTransReceivedTime.set(System.currentTimeMillis())

      if (data != null) {
        val rttMs =
          ((System.nanoTime() - data.timeNanos) / NANOS_PER_MILLI).toInt().coerceAtLeast(0)
        val prev = player.tracking.playerPing
        player.tracking.playerPing =
          if (prev == 0) rttMs else (prev * PING_EWMA_OLD_WEIGHT + rttMs) / PING_EWMA_TOTAL_WEIGHT
      }
    }
    return data != null
  }

  private fun isMojangStupid(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
    event: PacketReceiveEvent,
  ) {
    if (
      player.packetStateData.lastPacketWasTeleport ||
        player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21)
    ) {
      return
    }

    val location: Location = flying.location
    if (shouldProcessDuplicate(player, flying, location)) {
      handleDuplicatePacketAction(player, flying, location, event)
      player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = true
      applyDuplicateRotation(player, location)
    }
  }

  private fun shouldProcessDuplicate(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
    location: Location,
  ): Boolean {
    val threshold = player.getMovementThreshold()
    val inVehicle = player.compensatedEntities.self.riding != null
    val hasMovementAndRotation = flying.hasPositionChanged() && flying.hasRotationChanged()
    val sameGroundAndCloseClaim =
      flying.isOnGround == player.packetStateData.packetPlayerOnGround &&
        player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_17) &&
        player.packetStateData.duplicatePacketFilterPosition.distanceSquared(location.position) <
          threshold * threshold
    return hasMovementAndRotation && (sameGroundAndCloseClaim || inVehicle)
  }

  private fun handleDuplicatePacketAction(
    player: ShardPlayer,
    flying: WrapperPlayClientPlayerFlying,
    location: Location,
    event: PacketReceiveEvent,
  ) {
    val serverVersion = PacketEvents.getAPI().serverManager.version
    if (player.isForceCancelDuplicatePacket()) {
      event.isCancelled = true
      return
    }

    if (serverVersion.isOlderThanOrEquals(ServerVersion.V_1_9)) {
      if (player.isCancelDuplicatePacket()) {
        event.isCancelled = true
      }
      return
    }

    flying.location =
      Location(player.packetStateData.duplicatePacketFilterPosition, location.yaw, location.pitch)
    event.markForReEncode(true)
  }

  private fun applyDuplicateRotation(player: ShardPlayer, location: Location) {
    if (player.isIgnoreDuplicatePacketRotation()) return

    val movement = player.movement
    if (movement.yaw != location.yaw || movement.pitch != location.pitch) {
      movement.lastYaw = movement.yaw
      movement.lastPitch = movement.pitch
    }
    movement.yaw = location.yaw
    movement.pitch = location.pitch
  }

  private companion object {
    const val DEATH_ANIMATION_STATUS = 3
    // 1.7 rounding
    const val TELEPORT_EPSILON = 1.0E-7
    const val LIVING_FLAGS_INDEX_MODERN = 8
    const val LIVING_FLAGS_INDEX_POSE = 7
    const val LIVING_FLAGS_INDEX_LEGACY = 6
    const val LIVING_FLAGS_INDEX_ANCIENT = 5
    const val GLIDING_FLAG = 0x80
    const val SWIMMING_FLAG = 0x10
    const val HOTBAR_START_SLOT = 36
    const val OFFHAND_SLOT = 45
    const val DEFAULT_ATTACK_SPEED = 4.0f
    const val RIPTIDE_FLAG = 0x04
    const val USE_ITEM_FLAG = 0x01
    const val OFFHAND_FLAG = 0x02
    const val FIREWORK_ATTACHED_INDEX_MODERN = 9
    val AGEABLE_BABY_INDEX_RANGE = 15..17
    const val SIZE_METADATA_MIN_INDEX = 15
    const val GROUND_SEARCH_OFFSET = 0.5000001
    const val BODY_OFFSET = 0.5
    const val HEAD_OFFSET = 1.6
    const val NANOS_PER_MILLI = 1_000_000L
    const val PING_EWMA_OLD_WEIGHT = 4
    const val PING_EWMA_TOTAL_WEIGHT = 5
  }
}
