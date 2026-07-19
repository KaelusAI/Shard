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

import ac.shard.Shard
import ac.shard.checks.AbstractCheck
import ac.shard.checks.CheckData
import ac.shard.checks.CheckFactory
import ac.shard.checks.type.PacketCheck
import ac.shard.config.ConfigManager
import ac.shard.data.DataSession
import ac.shard.data.TickData
import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying

@CheckData(name = "DataCollector_Internal")
class DataCollectorCheck(
  shardPlayer: ShardPlayer,
  private val dataCollectorManager: DataCollectorManager,
  private val plugin: Shard,
  private val configManager: ConfigManager,
) : AbstractCheck(shardPlayer), PacketCheck {
  interface Factory : CheckFactory {
    override fun create(player: ShardPlayer): DataCollectorCheck
  }

  override fun onPacketReceive(event: PacketReceiveEvent) {
    val shardPlayer = shardPlayer
    val session: DataSession = dataCollectorManager.getSession(shardPlayer.uuid) ?: return
    if (WrapperPlayClientPlayerFlying.isFlying(event.packetType)) {
      val packetStateData = shardPlayer.packetStateData
      if (packetStateData.lastPacketWasTeleport || packetStateData.lastPacketWasServerRotation) {
        plugin.logger.info(
          "Skipping server-side rotation packet in data collection for player: ${shardPlayer.player.name}"
        )
      }
      if (packetStateData.shouldIgnoreFlyingTick) {
        return
      }

      if (shouldRecord(shardPlayer)) {
        session.addTick(TickData(shardPlayer))
      }
    }
  }

  private fun shouldRecord(shardPlayer: ShardPlayer): Boolean =
    shardPlayer.compensatedEntities.self.riding == null &&
      (configManager.aiContinuous ||
        shardPlayer.combat.ticksSinceAttack <= configManager.aiSequence)
}
