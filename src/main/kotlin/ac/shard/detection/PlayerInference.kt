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
package ac.shard.detection

import ac.shard.ai.label.LabelKey
import ac.shard.ai.stream.BlockReason
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.StreamProfile
import ac.shard.player.ShardPlayer

class PlayerInference(
  private val shardPlayer: ShardPlayer,
  private val services: InferenceServices,
) {
  val state = DetectionState(services.configManager)
  private val dispatcher = OutcomeDispatcher(shardPlayer, state, services, ::queryDisabledRegion)
  private val processor = VerdictProcessor(shardPlayer, state, services, dispatcher)
  private val session =
    StreamSession(shardPlayer, services, state, { inDisabledRegion }, processor::onVerdict)

  @Volatile
  var inDisabledRegion = false
    private set

  val streamBlockReason: BlockReason?
    get() = session.blockReason

  fun onDataTick() = session.onDataTick()

  fun onQuit() = session.close()

  fun inferenceProgressByModel(): List<Pair<ModelSpec, IntArray?>> = session.progressByModel()

  fun onPlayerThread() {
    inDisabledRegion = queryDisabledRegion()
  }

  internal fun onVerdict(profile: StreamProfile, model: ModelSpec, scores: DoubleArray) =
    processor.onVerdict(profile, model, scores)

  private fun queryDisabledRegion(): Boolean =
    services.configManager.settings.regions.worldGuard &&
      services.regionProvider.isPlayerInDisabledRegion(shardPlayer.player)

  companion object {
    const val NAME = "AI"
    const val UNATTRIBUTED_LABEL = LabelKey.UNATTRIBUTED
  }
}
