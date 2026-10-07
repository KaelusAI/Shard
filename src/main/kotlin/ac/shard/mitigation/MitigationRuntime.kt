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
import ac.shard.api.impl.Sessions
import ac.shard.api.impl.apiTier
import ac.shard.api.impl.event.MitigationChangeEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import ac.shard.utils.Messages
import ac.shard.utils.WallClock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private const val PERIOD_TICKS = 20L
private const val ANSWER_STALE_MILLIS = 3_000L
private const val DEFAULT_COMBAT_TICKS = 64
private const val RELOAD = "configuration reloaded"

class MitigationRuntime(
  private val messages: Messages,
  private val plugin: Shard,
  private val playerDataManager: PlayerDataManager,
  private val configManager: ConfigManager,
  private val alertManager: AlertManager,
  private val skip: MitigationSkip,
  private val engine: RuleEngine,
  private val stamps: HitStamps,
  private val debugManager: DebugManager,
  private val scheduler: SchedulerService,
  private val logStore: MitigationLogStore,
  private val settings: MitigationSettingsSource,
  private val clock: WallClock,
  private val events: ShardEvents,
  private val sessions: Sessions,
) {

  private var handle: TaskHandle? = null

  fun enable() {
    plugin.server.pluginManager.registerEvents(
      MitigationChannelListener(playerDataManager, stamps, events, sessions),
      plugin,
    )
    handle = scheduler.runTimer({ tick() }, PERIOD_TICKS, PERIOD_TICKS)
  }

  fun disable() {
    handle?.cancel()
    handle = null
    playerDataManager.getPlayers().forEach { release(it, async = false) }
    stamps.clear()
  }

  fun reload() {
    val fresh = settings().rules.associateBy { it.id }
    playerDataManager.getPlayers().forEach { shardPlayer ->
      val state = shardPlayer.mitigation
      val appliedId = state.applied?.id
      val before = state.appliedTier
      if (appliedId != null && fresh[appliedId] == null) {
        release(shardPlayer)
        announce(shardPlayer, RuleChange(appliedId, null, RELOAD), before)
        return@forEach
      }
      state.updatePhase { phase ->
        phase.copy(
          matched = phase.matched?.let { fresh[it.id] },
          applied = phase.applied?.let { fresh[it.id] },
          spent = phase.spent?.let { fresh[it.id] },
        )
      }
      if (state.appliedTier != before) {
        announce(shardPlayer, RuleChange(appliedId, appliedId, RELOAD), before)
      }
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
    val ai = shardPlayer.detection
    val heard = state.lastAnswerAtMillis
    val listening = heard != 0L && now - heard <= ANSWER_STALE_MILLIS
    return RuleFacts(
      score = state.score,
      buffer = ai.primaryBuffer,
      probability = if (listening) ai.lastCheatProbability else 0.0,
      answers = state.answers,
      sessions = state.history.sessions,
      days = state.history.days,
      onlineMillis = now - shardPlayer.joinTime,
      inCombat =
        shardPlayer.combat.ticksSinceAttack <=
          (configManager.streamProfile?.primary?.span ?: DEFAULT_COMBAT_TICKS),
      probabilityHolds = if (listening) state.probabilityHolds() else emptyMap(),
      labelBuffers = primaryOnly(ai.labelBufferSnapshot()),
      labelProbabilities = if (listening) primaryOnly(ai.lastLabelProbabilities) else emptyMap(),
    )
  }

  private fun primaryOnly(values: Map<String, Double>): Map<String, Double> = values.filterKeys {
    '/' !in it
  }

  private fun advance(shardPlayer: ShardPlayer) {
    val state = shardPlayer.mitigation
    val applying = state.appliedTier
    val wasApplied = state.applied
    val startedAt = state.appliedAtMillis
    val peak = state.peakScore

    val facts = factsFor(shardPlayer)
    val reason = skip.skipReason(shardPlayer)

    val change = engine.evaluate(state, facts, reason)
    if (state.applied !== wasApplied && wasApplied != null && startedAt != 0L) {
      log(shardPlayer, wasApplied, applying, startedAt, peak)
    }
    trackPeak(state, wasApplied)

    countTowardsRepeat(shardPlayer)

    if (change != null) announce(shardPlayer, change, applying)
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

  private fun trackPeak(state: MitigationState, wasApplied: MitigationRule?) {
    val score = state.score
    state.updatePhase { phase ->
      phase.copy(
        peakScore =
          when {
            phase.applied == null -> 0.0
            phase.applied !== wasApplied -> score
            else -> maxOf(phase.peakScore, score)
          }
      )
    }
  }

  private fun log(
    shardPlayer: ShardPlayer,
    rule: MitigationRule,
    tier: MitigationTier,
    startedAt: Long,
    peak: Double,
    async: Boolean = true,
  ) {
    if (async) {
      scheduler.runAsync { logStore.record(shardPlayer, rule, tier, startedAt, peak) }
    } else {
      logStore.record(shardPlayer, rule, tier, startedAt, peak)
    }
  }

  private fun release(shardPlayer: ShardPlayer, async: Boolean = true) {
    val state = shardPlayer.mitigation
    val running = state.applied
    if (running != null && state.appliedAtMillis != 0L) {
      log(
        shardPlayer,
        running,
        state.appliedTier,
        state.appliedAtMillis,
        maxOf(state.peakScore, state.score),
        async,
      )
    }
    val now = clock()
    state.updatePhase { RulePhase(onsetAtMillis = now, holdUntilMillis = now) }
  }

  private fun tellStaff(shardPlayer: ShardPlayer, was: MitigationTier) {
    val state = shardPlayer.mitigation
    val now = state.appliedTier
    if (now == was || !now.atLeast(FIRST_GAMEPLAY_TIER) || now.ordinal <= was.ordinal) return

    alertManager.send(
      messages.getMessage(
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

  private fun announce(shardPlayer: ShardPlayer, change: RuleChange, previous: MitigationTier) {
    val state = shardPlayer.mitigation
    debugManager.log(
      DebugCategory.MITIGATION,
      "${shardPlayer.player.name} ${change.from ?: "-"} -> ${change.to ?: "-"} " +
        "at ${format(state.score)}: ${change.reason}",
    )
    events.publish(
      MitigationChangeEventImpl(
        sessions.of(shardPlayer),
        apiTier(previous.name) ?: ac.shard.api.mitigation.MitigationTier.NONE,
        apiTier(state.appliedTier.name) ?: ac.shard.api.mitigation.MitigationTier.NONE,
        change.from,
        change.to,
        null,
      )
    )
  }

  private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
