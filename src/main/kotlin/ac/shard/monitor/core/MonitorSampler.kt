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

import ac.shard.ai.stream.BlockReason
import ac.shard.data.CollectManager
import ac.shard.detection.PlayerInference
import ac.shard.mitigation.EffectChannel
import ac.shard.player.PlayerDataManager
import java.time.Duration
import java.time.Instant
import java.util.Locale
import org.bukkit.entity.Player

class MonitorSampler(
  private val playerDataManager: PlayerDataManager,
  private val collectManager: CollectManager,
) {
  fun sample(target: Player): MonitorSample {
    val shardTarget = playerDataManager.getPlayer(target)
    val aiCheck = shardTarget?.ai
    val state = shardTarget?.detection
    return MonitorSample(
      targetId = target.uniqueId,
      targetName = target.name,
      dataPresent = shardTarget != null,
      aiActive = aiCheck != null,
      probability =
        MonitorLabelInfo.probabilityOfLeader(
          state?.labelBufferSnapshot().orEmpty(),
          state?.lastLabelProbabilities.orEmpty(),
          state?.lastCheatProbability ?: 0.0,
        ),
      buffer = state?.primaryBuffer ?: 0.0,
      rawPing = target.ping,
      damageMultiplier = shardTarget?.mitigation?.multiplierFor(EffectChannel.MELEE) ?: 1.0,
      prob90 = state?.prob90 ?: 0,
      collect = collectInfo(target),
      inference = aiCheck?.let(::inferenceInfo),
      leadingLabel = state?.let { leadingLabel(it.labelBufferSnapshot()) },
      labelBuffers = state?.labelBufferSnapshot().orEmpty(),
      labelProbabilities = state?.lastLabelProbabilities.orEmpty(),
      declaredLabels = state?.declaredLabels.orEmpty(),
      tier = shardTarget?.mitigation?.appliedTier?.name ?: "NONE",
      score = shardTarget?.mitigation?.score ?: 0.0,
      rule = shardTarget?.mitigation?.applied?.id.orEmpty(),
      appliedForMillis = appliedFor(shardTarget),
      model = state?.shownModelTitle.orEmpty(),
      models = state?.modelCards().orEmpty(),
    )
  }

  private fun leadingLabel(buffers: Map<String, Double>): MonitorLabelInfo? =
    MonitorLabelInfo.leading(buffers)

  private fun collectInfo(target: Player): MonitorCollectInfo? {
    val session = collectManager.getSession(target.uniqueId) ?: return null
    val elapsed = Duration.between(session.startTime, Instant.now())
    return MonitorCollectInfo(
      status =
        tickStatus(
          collectManager.getCurrentProgress(target.uniqueId),
          collectManager.getPostWindow(),
        ),
      label = session.label.lowercase(Locale.ROOT),
      windows = session.windowCount(),
      elapsed = "${elapsed.toMinutes()}m${elapsed.toSecondsPart()}s",
    )
  }

  private fun tickStatus(progress: Int, postWindow: Int): String =
    if (progress < 0) WAITING else "$progress/$postWindow"

  private fun inferenceInfo(aiCheck: PlayerInference): MonitorInferenceInfo {
    val blocked = aiCheck.streamBlockReason
    val models = if (blocked == null) aiCheck.inferenceProgressByModel() else emptyList()
    val states = models.map { (model, progress) ->
      model.displayTitle to if (progress == null) IDLE else "${progress[0]}/${progress[1]}"
    }
    val named = states.distinctBy { it.second }.size > 1
    val status =
      when {
        blocked != null -> BLOCK_WORDS.getValue(blocked)
        states.isEmpty() -> IDLE
        named -> states.joinToString(", ") { (title, state) -> "$title $state" }
        else -> states.first().second
      }
    return MonitorInferenceInfo(status, blocked in FAULTS, named)
  }

  private fun appliedFor(shardTarget: ac.shard.player.ShardPlayer?): Long {
    val state = shardTarget?.mitigation
    val since = state?.appliedAtMillis ?: 0L
    return if (state?.applied == null || since == 0L) {
      0L
    } else {
      (System.currentTimeMillis() - since).coerceAtLeast(0L)
    }
  }

  private companion object {
    const val WAITING = "waiting"
    const val IDLE = "idle"
    val FAULTS =
      setOf(
        BlockReason.NO_PROFILE,
        BlockReason.COOLDOWN,
        BlockReason.STREAM_LIMIT,
        BlockReason.PROTOCOL,
      )
    val BLOCK_WORDS =
      mapOf(
        BlockReason.NO_PROFILE to "no profile",
        BlockReason.DISABLED to "off",
        BlockReason.VEHICLE to "vehicle",
        BlockReason.REGION to "region",
        BlockReason.COOLDOWN to "paused",
        BlockReason.STREAM_LIMIT to "limited",
        BlockReason.PROTOCOL to "error",
      )
  }
}
