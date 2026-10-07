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
import ac.shard.ai.stream.ModelSpec
import ac.shard.alert.AlertType
import ac.shard.api.event.detection.FlagEvent
import ac.shard.api.event.detection.VerdictEvent
import ac.shard.api.impl.DetectionSnapshotView
import ac.shard.api.impl.LabelBufferView
import ac.shard.api.impl.PunishmentGroupView
import ac.shard.api.impl.SessionView
import ac.shard.api.impl.VerdictView
import ac.shard.api.impl.event.FlagEventImpl
import ac.shard.api.impl.event.VerdictEventImpl
import ac.shard.api.impl.labelIdOf
import ac.shard.debug.DebugCategory
import ac.shard.mitigation.EffectChannel
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionCheckMode
import ac.shard.utils.Message

class OutcomeDispatcher(
  private val shardPlayer: ShardPlayer,
  private val state: DetectionState,
  private val services: InferenceServices,
  private val inDisabledRegion: () -> Boolean,
) {
  private val configManager = services.configManager
  private val debugManager = services.debugManager

  fun deliver(outcome: Outcome) {
    val result = outcome.result
    val multiplier = shardPlayer.mitigation.multiplierFor(EffectChannel.MELEE)
    if (debugManager.isEnabled(DebugCategory.AI_PROBABILITY)) {
      debugManager.log(
        DebugCategory.AI_PROBABILITY,
        buildAiProbabilityDebugMessage(
          playerName =
            "${shardPlayer.player.name} | ${shardPlayer.user.clientVersion.releaseName} | " +
              outcome.model.displayTitle,
          probability = outcome.probability,
          oldBuffer = result.before,
          newBuffer = result.after,
          damageMultiplier = multiplier,
        ),
      )
    }
    if (outcome.suspicious) alertSuspicious(outcome)
    val flagged = punish(outcome)
    publishVerdict(outcome.model, outcome.owner)
    services.predictions.publish(
      Prediction(
        shardPlayer.uuid,
        shardPlayer.player.name,
        outcome.probability,
        result.before,
        result.after,
        multiplier,
        state.prob90,
        flagged,
        state.labelBufferSnapshot(),
        state.lastLabelProbabilities,
        outcome.model.displayTitle,
        outcome.primary,
        outcome.model.id,
      )
    )
  }

  private fun punish(outcome: Outcome): Boolean {
    val punishable = outcome.result.crossed.filterKeys { outcome.role.punish?.covers(it) == true }
    if (punishable.isEmpty() || skippedByRegion()) return false
    val owner = outcome.owner
    val labels = punishable.keys.map { DetectionKey(owner.id, it) }.toSet()
    val cards = state.modelCards()
    val detail =
      if (cards.isNotEmpty()) {
        buildAiFlagSummary(outcome.model.id, outcome.probability, cards)
      } else {
        buildAiFlagDebug(
          outcome.probability,
          punishable.mapKeys { state.address(DetectionKey(owner.id, it.key)) },
          state.labelBufferSnapshot(),
          configManager.streamProfile?.primary?.id,
          outcome.model.id,
        )
      }
    return flag(detail, labels, owner, punishable)
  }

  private fun skippedByRegion(): Boolean {
    val skipped =
      configManager.settings.regions.mode == RegionCheckMode.SKIP_PUNISHMENT && inDisabledRegion()
    if (skipped) {
      debugManager.log(
        DebugCategory.WORLDGUARD,
        "${shardPlayer.player.name} is in a disabled region. Skipping punishment.",
      )
    }
    return skipped
  }

  @Suppress("ReturnCount")
  private fun flag(
    debug: String,
    labels: Set<DetectionKey>,
    owner: ModelSpec,
    crossed: Map<String, Double>,
  ): Boolean {
    val exempts = shardPlayer.exemptManager
    if (exempts.isDisabled(shardPlayer) || exempts.isExempt(shardPlayer)) {
      services.logger.fine("[Punish] Flag on ${shardPlayer.name} ignored: player is exempt")
      return false
    }
    if (services.events.wants(FlagEvent::class.java)) {
      val keys = services.punishments.groupsFor(labels).map { it.key }.toSet()
      val groups =
        PunishmentGroupView.tree(configManager.punishmentTree.root).filter {
          it.key() in keys
        }
      val settings = EffectiveSettings.of(owner, configManager.settings.localAi)
      val buffers = crossed.map { (label, value) ->
        LabelBufferView(owner.id, labelIdOf(owner.id, label), value, settings.buffer(label).flag)
      }
      val event = FlagEventImpl(session(), owner.id, buffers, groups)
      if (services.events.fire(event).isCancelled) return false
    }
    services.punishments.handleFlag(shardPlayer, PlayerInference.NAME, labels, debug)
    return true
  }

  private fun session() = SessionView(shardPlayer, configManager, services.mitigationSkip)

  private fun publishVerdict(model: ModelSpec, owner: ModelSpec) {
    if (!services.events.wants(VerdictEvent::class.java)) return
    val verdict = state.lastVerdicts[model.id] ?: return
    val snapshot =
      DetectionSnapshotView.stateOf(
        owner,
        state.bufferSnapshot(),
        EffectiveSettings.of(owner, configManager.settings.localAi),
      )
    services.events.publish(VerdictEventImpl(session(), VerdictView.of(verdict), snapshot))
  }

  private fun alertSuspicious(outcome: Outcome) {
    services.alertManager.send(
      services.messages.getMessage(
        Message.SUSPICIOUS_ALERT_TRIGGERED,
        "player",
        shardPlayer.player.name,
        "buffer",
        formatAiBuffer(outcome.result.after),
        "label",
        state.leadingLabelName(outcome.owner),
      ),
      AlertType.SUSPICIOUS,
    )
  }
}
