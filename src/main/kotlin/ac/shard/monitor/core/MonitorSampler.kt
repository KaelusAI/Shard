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
package ac.shard.monitor.core

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.player.PlayerDataManager
import org.bukkit.entity.Player

class MonitorSampler(private val playerDataManager: PlayerDataManager) {
  fun sample(target: Player): MonitorSample {
    val shardTarget = playerDataManager.getPlayer(target)
    val aiCheck = shardTarget?.checkManager?.getCheck(AiCheck::class.java)
    return MonitorSample(
      targetId = target.uniqueId,
      targetName = target.name,
      dataPresent = shardTarget != null,
      aiActive = aiCheck != null,
      probability = aiCheck?.lastProbability ?: 0.0,
      buffer = aiCheck?.buffer ?: 0.0,
      rawPing = target.ping,
      damageMultiplier = shardTarget?.combat?.damageMultiplier ?: 1.0,
      prob90 = aiCheck?.prob90 ?: 0,
    )
  }
}
