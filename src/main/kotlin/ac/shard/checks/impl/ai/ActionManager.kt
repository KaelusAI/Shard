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
package ac.shard.checks.impl.ai

import ac.shard.checks.AbstractCheck
import ac.shard.checks.CheckData
import ac.shard.checks.CheckFactory
import ac.shard.checks.type.PacketCheck
import ac.shard.database.DatabaseManager
import ac.shard.entity.PacketEntity
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity

@CheckData(name = "ActionManager_Internal")
class ActionManager(
  player: ShardPlayer,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
) : AbstractCheck(player), PacketCheck {
  interface Factory : CheckFactory {
    override fun create(player: ShardPlayer): ActionManager
  }

  fun onAttack() {
    shardPlayer.combat.ticksSinceAttack = 0
    if (shardPlayer.combat.hasAttacked) return
    shardPlayer.combat.hasAttacked = true
    val attacker = shardPlayer.uuid
    val now = System.currentTimeMillis()
    scheduler.runAsync { databaseManager.database.recordAttack(attacker, now) }
  }

  override fun onPacketReceive(event: PacketReceiveEvent) {
    when (event.packetType) {
      PacketType.Play.Client.INTERACT_ENTITY -> {
        val interact = WrapperPlayClientInteractEntity(event)
        if (interact.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
          handleAttack(interact.entityId)
        }
      }
      // 26.1 split the attack out of INTERACT_ENTITY.
      PacketType.Play.Client.ATTACK -> handleAttack(WrapperPlayClientAttack(event).entityId)
      else -> Unit
    }
  }

  private fun handleAttack(entityId: Int) {
    val entity: PacketEntity = shardPlayer.compensatedEntities.getEntity(entityId) ?: return
    if (entity.isPlayer) {
      onAttack()
    }
  }
}
