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
package ac.shard.mitigation

import ac.shard.config.ConfigManager
import ac.shard.monitor.hud.MILLIS_PER_TICK
import ac.shard.player.ShardPlayer

class MitigationScorer(
  private val configManager: ConfigManager,
  private val settings: () -> MitigationSettings,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  fun record(shardPlayer: ShardPlayer, probability: Double) {
    val config = settings()
    val now = clock()
    val contribution =
      ScoreMath.contribution(
        probability,
        configManager.aiStep,
        (configManager.aiPreWindow + configManager.aiPostWindow),
        config.score,
      )
    shardPlayer.mitigation.record(contribution, probability, now, config.score)
    shardPlayer.mitigation.noteProbability(
      probability,
      now,
      HoldAccounting(
        config.probabilityHolds,
        (configManager.aiPreWindow + configManager.aiPostWindow) * MILLIS_PER_TICK,
        config.score.forgetRate,
      ),
    )
  }

  fun leak(shardPlayer: ShardPlayer) {
    shardPlayer.mitigation.leak(clock(), settings().score)
  }

  fun freeze(shardPlayer: ShardPlayer) {
    shardPlayer.mitigation.freeze(clock(), settings().score)
  }

  fun thaw(shardPlayer: ShardPlayer) {
    shardPlayer.mitigation.thaw(clock())
  }
}
