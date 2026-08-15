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
@file:Suppress("ReturnCount")

package ac.shard.mitigation

import kotlin.random.Random

data class RuleChange(val from: String?, val to: String?, val reason: String)

class RuleEngine(
  private val settings: () -> MitigationSettings,
  private val clock: () -> Long,
  private val random: Random = Random.Default,
) {

  fun evaluate(state: MitigationState, facts: RuleFacts, skip: SkipReason?): RuleChange? {
    val config = settings()
    val now = clock()
    state.leak(now, config.score)

    if (skip != null || !config.enabled) {
      val stopped = release(state, now, skip?.name?.lowercase() ?: TURNED_OFF)
      state.activeEffects = emptyMap()
      return stopped
    }

    val pick = pick(state, facts, config.rules, now)
    var change: RuleChange? = null

    if (pick !== state.matched) {
      val from = state.matched
      state.matched = pick
      state.matchedSinceMillis = now
      state.onsetAtMillis = now + delayFor(from, pick)
      state.holdUntilMillis = now + (pick?.timing?.holdMillis ?: 0L)
      change = RuleChange(from?.id, pick?.id, reasonFor(from, pick))
    }

    settle(state, facts, now)
    restate(state, facts)
    return change
  }

  private fun pick(
    state: MitigationState,
    facts: RuleFacts,
    rules: List<MitigationRule>,
    now: Long,
  ): MitigationRule? {
    if (state.spent?.matches(facts) == false) state.spent = null
    val best = rules.firstOrNull { it.matches(facts) && it !== state.spent }
    val current = state.matched ?: return best

    if (best != null && best.order < current.order) return best
    if (expired(state, current, facts, now)) {
      state.spent = current
      return best.takeIf { it !== current }
    }
    if (!current.releases(facts)) return current
    if (now < state.holdUntilMillis) return current
    return best
  }

  private fun expired(
    state: MitigationState,
    current: MitigationRule,
    facts: RuleFacts,
    now: Long,
  ): Boolean {
    if (state.appliedAtMillis == 0L) return false
    val span = current.timing.maxMillis
    val windows = current.timing.maxAnswers
    return (span > 0L && now - state.appliedAtMillis >= span) ||
      (windows > 0L && facts.answers - state.answersAtApply >= windows)
  }

  private fun delayFor(from: MitigationRule?, to: MitigationRule?): Long {
    if (to == null) {
      val jitter = from?.timing?.releaseJitterMaxMillis ?: 0L
      return if (jitter <= 0L) 0L else random.nextLong(0L, jitter + 1)
    }
    val low = to.timing.delayMinMillis
    val high = to.timing.delayMaxMillis
    return if (high <= low) low else random.nextLong(low, high + 1)
  }

  private fun settle(state: MitigationState, facts: RuleFacts, now: Long) {
    if (state.applied === state.matched) return
    if (now < state.onsetAtMillis) return

    val target = state.matched
    if (target != null && !target.timing.startsInCombat && facts.inCombat) return

    state.applied = target
    state.appliedAtMillis = if (target == null) 0L else now
    state.answersAtApply = facts.answers
  }

  private fun restate(state: MitigationState, facts: RuleFacts) {
    state.activeEffects = state.applied?.effects?.resolve(facts) ?: emptyMap()
  }

  private fun release(state: MitigationState, now: Long, reason: String): RuleChange? {
    if (state.matched == null && state.applied == null) return null
    val from = state.matched ?: state.applied
    state.matched = null
    state.applied = null
    state.onsetAtMillis = now
    state.holdUntilMillis = now
    state.appliedAtMillis = 0L
    return RuleChange(from?.id, null, reason)
  }

  private fun reasonFor(from: MitigationRule?, to: MitigationRule?): String =
    when {
      to == null -> "${from?.id} no longer holds"
      from == null -> "${to.id} matched"
      else -> "${to.id} took over from ${from.id}"
    }

  private companion object {
    const val TURNED_OFF = "mitigations are switched off"
  }
}
