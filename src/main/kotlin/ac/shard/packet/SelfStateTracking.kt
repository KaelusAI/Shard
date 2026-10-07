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

import ac.shard.data.TickData
import ac.shard.player.ShardPlayer
import ac.shard.player.state.TrackingState
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.potion.PotionType
import com.github.retrooper.packetevents.protocol.potion.PotionTypes
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth

internal object SelfStateTracking {
  fun onVelocity(event: PacketSendEvent, player: ShardPlayer) {
    val vel = WrapperPlayServerEntityVelocity(event)
    if (vel.entityId != player.entityId) return
    val velocity = vel.velocity
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      player.tracking.onKnockback(velocity.x, velocity.y, velocity.z)
    }
  }

  fun onExplosion(event: PacketSendEvent, player: ShardPlayer) {
    val kb = WrapperPlayServerExplosion(event).playerMotion
    if (kb == null || !kb.isNonZero()) return
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      player.tracking.onExplosion(kb.x, kb.y, kb.z)
      player.tracking.raiseWindowStart(TickData.START_EXPLOSION_RECEIVED)
    }
  }

  fun onEffect(event: PacketSendEvent, player: ShardPlayer) {
    val effect = WrapperPlayServerEntityEffect(event)
    if (effect.entityId != player.entityId) return
    val potionType = effect.potionType
    val amplifier = effect.effectAmplifier
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      applyPotion(player.tracking, potionType, amplifier)
    }
  }

  fun onRemoveEffect(event: PacketSendEvent, player: ShardPlayer) {
    val remove = WrapperPlayServerRemoveEntityEffect(event)
    if (remove.entityId != player.entityId) return
    val potionType = remove.potionType
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      clearPotion(player.tracking, potionType)
    }
  }

  fun onAbilities(event: PacketSendEvent, player: ShardPlayer) {
    val isFlying = WrapperPlayServerPlayerAbilities(event).isFlying
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      player.tracking.flying = isFlying
    }
  }

  fun onHealth(event: PacketSendEvent, player: ShardPlayer) {
    val update = WrapperPlayServerUpdateHealth(event)
    val food = update.food
    val newHealth = update.health
    player.latencyUtils.addRealTimeTask(player.bracketTransaction(event)) {
      val tracking = player.tracking
      val lost = tracking.health - newHealth
      if (lost > 0f) {
        tracking.damageTakenThisTick = lost
        tracking.ticksSinceDamage = 0
      }
      tracking.health = newHealth
      tracking.foodLevel = food
    }
  }

  fun onGameState(event: PacketSendEvent, player: ShardPlayer) {
    val gs = WrapperPlayServerChangeGameState(event)
    if (gs.reason == WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE) {
      player.tracking.gameMode = gs.value.toInt()
    }
  }

  fun onJoinGame(event: PacketSendEvent, player: ShardPlayer) {
    val join = WrapperPlayServerJoinGame(event)
    player.tracking.onSequenceBreak()
    val gameMode = join.gameMode
    player.entityId = join.entityId
    player.gameMode = gameMode
    player.tracking.gameMode = gameMode.ordinal
    player.compensatedEntities.clear()
    player.compensatedWorld.clear()
    if (PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_17)) {
      runCatching { join.dimensionType.getMinY(player.user.clientVersion) }
        .onSuccess { player.compensatedWorld.updateMinHeight(it) }
    }
    event.tasksAfterSend.add(Runnable { player.sendTransaction() })
  }

  fun onRespawn(event: PacketSendEvent, player: ShardPlayer) {
    val respawn = WrapperPlayServerRespawn(event)
    player.tracking.onSequenceBreak()
    player.gameMode = respawn.gameMode
    player.tracking.gameMode = respawn.gameMode.ordinal
    val dimensionMinY = runCatching { respawn.dimensionType.getMinY(player.user.clientVersion) }
    player.latencyUtils.addRealTimeTask(
      player.bracketTransaction(event),
      Runnable {
        player.compensatedEntities.clear()
        player.compensatedWorld.clear()
        dimensionMinY.onSuccess { player.compensatedWorld.updateMinHeight(it) }
      },
    )
  }
}

private fun Vector3f.isNonZero(): Boolean = x != 0f || y != 0f || z != 0f

private fun applyPotion(tracking: TrackingState, potionType: PotionType?, amplifier: Int) {
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

private fun clearPotion(tracking: TrackingState, potionType: PotionType?) {
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
