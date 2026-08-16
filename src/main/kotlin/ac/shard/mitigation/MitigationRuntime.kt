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
@file:Suppress("LongParameterList", "TooManyFunctions")

package ac.shard.mitigation

import ac.shard.Shard
import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.event.MitigationRuleEvent
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigManager
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private const val PERIOD_TICKS = 20L
private const val NO_RULE = "none"
private const val ANSWER_STALE_MILLIS = 3_000L

class MitigationRuntime(
  private val plugin: Shard,
  private val playerDataManager: PlayerDataManager,
  private val configManager: ConfigManager,
  private val alertManager: AlertManager,
  private val skip: MitigationSkip,
  private val engine: RuleEngine,
  private val damageProcessor: MitigationDamageProcessor,
  private val stamps: HitStamps,
  private val debugManager: DebugManager,
  private val scheduler: SchedulerService,
  private val settings: () -> MitigationSettings,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private var handle: TaskHandle? = null

  fun enable() {
    plugin.server.pluginManager.registerEvents(
      MitigationChannelListener(playerDataManager, stamps),
      plugin,
    )
    handle = scheduler.runTimer({ tick() }, PERIOD_TICKS, PERIOD_TICKS)
  }

  fun disable() {
    handle?.cancel()
    handle = null
    playerDataManager.getPlayers().forEach { release(it) }
    stamps.clear()
  }

  fun reload() {
    val fresh = settings().rules.associateBy { it.id }
    playerDataManager.getPlayers().forEach { shardPlayer ->
      val state = shardPlayer.mitigation
      val running = state.matched ?: state.applied
      if (running != null && fresh[running.id] == null) {
        release(shardPlayer)
        return@forEach
      }
      state.matched = state.matched?.let { fresh.getValue(it.id) }
      state.applied = state.applied?.let { fresh.getValue(it.id) }
      state.spent = state.spent?.let { fresh[it.id] }
    }
  }

  fun clearFor(shardPlayer: ShardPlayer) {
    release(shardPlayer)
  }

  internal fun tick() {
    playerDataManager.getPlayers().forEach(::advance)
  }

  fun factsFor(shardPlayer: ShardPlayer): RuleFacts {
    val now = clock()
    val state = shardPlayer.mitigation
    val aiCheck = shardPlayer.checkManager.getCheck(AiCheck::class.java)
    val heard = state.lastAnswerAtMillis
    val listening = heard != 0L && now - heard <= ANSWER_STALE_MILLIS
    return RuleFacts(
      score = state.score,
      buffer = aiCheck?.buffer ?: 0.0,
      probability = if (listening) aiCheck?.lastProbability ?: 0.0 else 0.0,
      answers = state.answers,
      sessions = state.history.sessions,
      days = state.history.days,
      onlineMillis = now - shardPlayer.joinTime,
      inCombat = shardPlayer.combat.ticksSinceAttack <= configManager.aiSequence,
      probabilityHolds = if (listening) state.probabilityHolds() else emptyMap(),
    )
  }

  private fun advance(shardPlayer: ShardPlayer) {
    val config = settings()
    val state = shardPlayer.mitigation
    val applying = state.appliedTier

    val facts = factsFor(shardPlayer)
    val enoughAnswers = state.answers >= config.score.minAnswers
    val reason =
      skip.skipReason(shardPlayer) ?: (SkipReason.TOO_FEW_ANSWERS.takeIf { !enoughAnswers })

    val change = engine.evaluate(state, facts, reason)

    countTowardsRepeat(shardPlayer)
    damageProcessor.refresh(shardPlayer)

    if (change != null) announce(shardPlayer, change)
    tellStaff(shardPlayer, applying)
  }

  private fun countTowardsRepeat(shardPlayer: ShardPlayer) {
    val state = shardPlayer.mitigation
    if (state.countedThisSession || state.matched == null) return
    state.countedThisSession = true

    val today =
      Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    val seen = state.history
    val firstEver = seen.sessions == 0
    state.history =
      ScoreHistory(
        sessions = seen.sessions + 1,
        days = if (!firstEver && today == seen.lastDay) seen.days else seen.days + 1,
        lastDay = today,
      )
  }

  private fun release(shardPlayer: ShardPlayer) {
    val state = shardPlayer.mitigation
    state.matched = null
    state.applied = null
    state.onsetAtMillis = clock()
    state.holdUntilMillis = clock()
    state.appliedAtMillis = 0L
    state.spent = null
    state.activeEffects = emptyMap()
    shardPlayer.combat.damageMultiplier = 1.0
  }

  private fun tellStaff(shardPlayer: ShardPlayer, was: MitigationTier) {
    val state = shardPlayer.mitigation
    val now = state.appliedTier
    if (now == was || !now.atLeast(FIRST_GAMEPLAY_TIER) || now.ordinal <= was.ordinal) return

    alertManager.send(
      MessageUtil.getMessage(
        Message.MITIGATIONS_ALERT,
        "player",
        shardPlayer.player.name,
        "tier",
        state.tierName,
        "score",
        format(state.score),
        "active",
        state.applied?.id ?: "-",
      ),
      AlertType.MITIGATION,
    )
  }

  private fun announce(shardPlayer: ShardPlayer, change: RuleChange) {
    val state = shardPlayer.mitigation
    debugManager.log(
      DebugCategory.MITIGATION,
      "${shardPlayer.player.name} ${change.from ?: "-"} -> ${change.to ?: "-"} " +
        "at ${format(state.score)}: ${change.reason}",
    )
    shardPlayer.eventBus.post(
      MitigationRuleEvent(
        shardPlayer.uuid,
        shardPlayer.player.name,
        change.from ?: NO_RULE,
        change.to ?: NO_RULE,
        state.score,
        change.reason,
      )
    )
  }

  private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
