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

import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelledVerdict
import ac.shard.ai.label.VerdictResolver
import ac.shard.ai.stream.ModelRole
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.RoleSpec
import ac.shard.ai.stream.StreamProfile
import ac.shard.player.ShardPlayer

@Suppress("LongParameterList")
class Outcome(
  val model: ModelSpec,
  val owner: ModelSpec,
  val role: ModelRole,
  val result: FeedResult,
  val probability: Double,
  val primary: Boolean,
  val suspicious: Boolean,
)

class VerdictProcessor(
  private val shardPlayer: ShardPlayer,
  private val state: DetectionState,
  private val services: InferenceServices,
  private val dispatcher: OutcomeDispatcher,
) {
  private val configManager = services.configManager

  fun onVerdict(profile: StreamProfile, model: ModelSpec, scores: DoubleArray) {
    state.reconcile()
    val settings = EffectiveSettings.of(model, configManager.settings.localAi)
    val verdict = resolve(model, settings, scores)
    val probability = scores.maxOrNull() ?: 0.0
    val (owner, ownerSettings) = bufferOwner(profile, model, settings)
    val result = state.feed(model, owner, verdict, settings, ownerSettings)
    val primary = model.id == profile.primary.id
    state.note(model, primary, verdict, settings, probability)
    if (verdict.attributed) {
      settings.role.mitigate?.let { mitigate(profile, model, it, verdict.values, result) }
    }
    val outcome =
      Outcome(
        model,
        owner ?: model,
        ownerSettings.role,
        result,
        probability,
        primary,
        suspicious(ownerSettings.role.alert, result),
      )
    services.scheduler.runSync(shardPlayer.player) { dispatcher.deliver(outcome) }
  }

  private fun suspicious(alert: RoleSpec?, result: FeedResult): Boolean {
    val at = alert?.buffer
    return when {
      alert == null -> false
      at == null -> result.crossed.keys.any(alert::covers)
      else -> result.after(alert) > at && result.before(alert) <= at
    }
  }

  private fun bufferOwner(
    profile: StreamProfile,
    model: ModelSpec,
    settings: EffectiveSettings,
  ): Pair<ModelSpec?, EffectiveSettings> {
    val owner = model.buffer.shared?.let { id -> profile.active.firstOrNull { it.id == id } }
    return owner to
      (owner?.let { EffectiveSettings.of(it, configManager.settings.localAi) } ?: settings)
  }

  private fun mitigate(
    profile: StreamProfile,
    model: ModelSpec,
    role: RoleSpec,
    values: Map<String, Double>,
    result: FeedResult,
  ) {
    val covered = values.filterKeys(role::covers)
    val cheat = covered.values.maxOrNull() ?: return
    if (result.after(role) < (role.buffer ?: 0.0)) return
    val own = covered.mapKeys { state.address(DetectionKey(model.id, it.key)) }
    val local = configManager.settings.localAi
    val mitigating = profile.active.filter { EffectiveSettings.of(it, local).role.mitigate != null }
    services.mitigationScorer.record(shardPlayer, profile, model, cheat, own, mitigating)
  }

  private fun resolve(
    model: ModelSpec,
    settings: EffectiveSettings,
    scores: DoubleArray,
  ): LabelledVerdict {
    val resolver =
      VerdictResolver(
        settings = {
          VerdictResolver.Settings(
            labels = model.labels,
            mode = settings.labelMode.takeUnless { model.singleHead } ?: LabelMode.SINGLE,
            split = settings.split,
            maxTracked = settings.maxTracked,
            legitClasses = model.legitLabels,
            thresholdedLabels = model.thresholds.keys,
          )
        },
        warn = { services.logger.warning(it) },
      )
    val named = if (model.singleHead) null else model.labels.zip(scores.toList()).toMap()
    return resolver.resolve(
      if (model.singleHead) null else scores.toList(),
      scores.maxOrNull() ?: 0.0,
      named,
    )
  }
}
