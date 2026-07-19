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

import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.player.TransactionStamp
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation
import org.bukkit.entity.Player

private const val FULL_CIRCLE_DEGREES = 360f
private const val MAX_PITCH = 90f

class PacketListener(private val playerDataManager: PlayerDataManager) : PacketListenerAbstract() {
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
    if (!flying.hasPositionChanged() || player.pendingTeleports.isEmpty()) {
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

      val epsilon = 1.0E-7
      if (
        kotlin.math.abs(flyingLocation.x - expectedX) < epsilon &&
          kotlin.math.abs(flyingLocation.y - expectedY) < epsilon &&
          kotlin.math.abs(flyingLocation.z - expectedZ) < epsilon
      ) {
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

    if (WrapperPlayClientPlayerFlying.isFlying(event.packetType)) {
      handleFlying(event, shardPlayer)
    }

    if (event.isCancelled) {
      resetFlags(shardPlayer)
      return
    }

    if (
      shardPlayer.packetStateData.lastPacketWasTeleport ||
        shardPlayer.packetStateData.lastPacketWasServerRotation
    ) {
      updatePlayerState(shardPlayer, WrapperPlayClientPlayerFlying(event))
    }

    if (
      WrapperPlayClientPlayerFlying.isFlying(event.packetType) &&
        !shardPlayer.packetStateData.shouldIgnoreFlyingTick
    ) {
      shardPlayer.combat.ticksSinceAttack = shardPlayer.combat.ticksSinceAttack + 1
    }

    shardPlayer.checkManager.onPacketReceive(event)

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
      val pong = WrapperPlayClientPong(event)
      val id = pong.id.toShort()
      if (addTransactionResponse(shardPlayer, id)) {
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

    isMojangStupid(shardPlayer, flying, event)

    if (!event.isCancelled && !teleported && !serverRotated) {
      processRotation(shardPlayer, flying)
    }

    if (!teleported) {
      shardPlayer.packetStateData.packetPlayerOnGround = flying.isOnGround
    }
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
      val newYaw = packet.location.yaw
      val newPitch = packet.location.pitch

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
            handleSetPassengers(WrapperPlayServerSetPassengers(event), shardPlayer)
          PacketType.Play.Server.DESTROY_ENTITIES ->
            handleDestroyEntities(WrapperPlayServerDestroyEntities(event), shardPlayer)
          PacketType.Play.Server.JOIN_GAME ->
            handleJoinGame(WrapperPlayServerJoinGame(event), shardPlayer)
          PacketType.Play.Server.RESPAWN -> handleRespawn(shardPlayer)
          PacketType.Play.Server.PLAYER_POSITION_AND_LOOK ->
            handlePositionAndLook(WrapperPlayServerPlayerPositionAndLook(event), shardPlayer)
          PacketType.Play.Server.PLAYER_ROTATION ->
            handlePlayerRotation(WrapperPlayServerPlayerRotation(event), shardPlayer)
          else -> Unit
        }
      }
      dropWrapperUnlessRewritten(event)
    }
  }

  private fun handleWindowConfirmation(
    confirmation: WrapperPlayServerWindowConfirmation,
    shardPlayer: ShardPlayer,
  ) {
    val id = confirmation.actionId
    val transactions = shardPlayer.transactions
    if (id <= 0 && transactions.didWeSendThatTrans.remove(id)) {
      transactions.transactionsSent.add(TransactionStamp(id, System.nanoTime()))
      transactions.lastTransactionSent.getAndIncrement()
    }
  }

  private fun handlePing(ping: WrapperPlayServerPing, shardPlayer: ShardPlayer) {
    val id = ping.id
    val transactions = shardPlayer.transactions
    if (id == id.toShort().toInt() && transactions.didWeSendThatTrans.remove(id.toShort())) {
      transactions.transactionsSent.add(TransactionStamp(id.toShort(), System.nanoTime()))
      transactions.lastTransactionSent.getAndIncrement()
    }
  }

  private fun handleSpawnEntity(spawn: WrapperPlayServerSpawnEntity, shardPlayer: ShardPlayer) {
    shardPlayer.compensatedEntities.addEntity(
      spawn.entityId,
      spawn.uuid.orElse(null),
      spawn.entityType,
    )
  }

  private fun handleSpawnLivingEntity(
    spawn: WrapperPlayServerSpawnLivingEntity,
    shardPlayer: ShardPlayer,
  ) {
    shardPlayer.compensatedEntities.addEntity(spawn.entityId, spawn.entityUUID, spawn.entityType)
  }

  private fun handleSpawnPainting(spawn: WrapperPlayServerSpawnPainting, shardPlayer: ShardPlayer) {
    shardPlayer.compensatedEntities.addEntity(spawn.entityId, spawn.uuid, EntityTypes.PAINTING)
  }

  private fun handleSpawnPlayer(spawn: WrapperPlayServerSpawnPlayer, shardPlayer: ShardPlayer) {
    shardPlayer.compensatedEntities.addEntity(spawn.entityId, spawn.uuid, EntityTypes.PLAYER)
  }

  private fun handleSetPassengers(
    wrapper: WrapperPlayServerSetPassengers,
    shardPlayer: ShardPlayer,
  ) {
    val self = shardPlayer.compensatedEntities.self
    val vehicle = shardPlayer.compensatedEntities.getEntity(wrapper.entityId) ?: return
    if (wrapper.passengers.contains(shardPlayer.entityId)) {
      self.mount(vehicle)
    } else if (self.riding === vehicle) {
      self.eject()
    }
  }

  private fun handleDestroyEntities(
    destroy: WrapperPlayServerDestroyEntities,
    shardPlayer: ShardPlayer,
  ) {
    val self = shardPlayer.compensatedEntities.self
    for (id in destroy.entityIds) {
      if (self.riding === shardPlayer.compensatedEntities.getEntity(id)) {
        self.eject()
      }
      shardPlayer.compensatedEntities.removeEntity(id)
    }
  }

  private fun handleJoinGame(join: WrapperPlayServerJoinGame, shardPlayer: ShardPlayer) {
    shardPlayer.entityId = join.entityId
    shardPlayer.gameMode = join.gameMode
    shardPlayer.compensatedEntities.clear()
  }

  private fun handleRespawn(shardPlayer: ShardPlayer) {
    shardPlayer.compensatedEntities.clear()
  }

  private fun handlePositionAndLook(
    wrapper: WrapperPlayServerPlayerPositionAndLook,
    shardPlayer: ShardPlayer,
  ) {
    shardPlayer.sendTransaction()
    val transactionId = shardPlayer.transactions.lastTransactionSent.get()
    val location = Vector3d(wrapper.x, wrapper.y, wrapper.z)
    val flags = wrapper.relativeFlags
    shardPlayer.pendingTeleports.add(ShardPlayer.TeleportData(location, flags, transactionId))
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
}
