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

import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.StreamProfile
import ac.shard.monitor.hud.MILLIS_PER_TICK
import ac.shard.player.ShardPlayer
import ac.shard.utils.WallClock

class MitigationScorer(
  private val settings: MitigationSettingsSource,
  private val clock: WallClock,
) {

  @Suppress("LongParameterList")
  fun record(
    shardPlayer: ShardPlayer,
    profile: StreamProfile,
    model: ModelSpec,
    probability: Double,
    labels: Map<String, Double> = emptyMap(),
    mitigating: List<ModelSpec> = profile.active.filter { it.role.mitigate != null },
  ) {
    val config = settings()
    val now = clock()
    val weightSum = mitigating.sumOf { it.mitigationWeight }
    val share = if (weightSum > 0.0) model.mitigationWeight / weightSum else 0.0
    val contribution =
      share * ScoreMath.contribution(probability, model.cadence, profile.primary.span, config.score)
    val lead =
      (mitigating.firstOrNull { it.id == profile.primary.id } ?: mitigating.firstOrNull())?.id ==
        model.id
    shardPlayer.mitigation.record(contribution, probability, now, config.score, answered = lead)
    if (!lead) return
    shardPlayer.mitigation.noteProbability(
      probability,
      now,
      HoldAccounting(
        config.probabilityHolds,
        model.span * MILLIS_PER_TICK,
        config.score.forgetRate,
      ),
      labels,
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
