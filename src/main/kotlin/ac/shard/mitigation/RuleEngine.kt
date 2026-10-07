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

import ac.shard.utils.WallClock
import kotlin.random.Random

data class RuleChange(val from: String?, val to: String?, val reason: String)

class RuleEngine(
  private val settings: MitigationSettingsSource,
  private val clock: WallClock,
  private val random: Random,
) {

  fun evaluate(state: MitigationState, facts: RuleFacts, skip: SkipReason?): RuleChange? {
    val config = settings()
    val now = clock()
    state.leak(now, config.score)
    return state.transition { phase ->
      if (skip != null || !config.enabled) {
        val (stopped, change) = release(phase, now, skip?.name?.lowercase() ?: TURNED_OFF)
        stopped.copy(activeEffects = emptyMap()) to change
      } else {
        step(phase, facts, config.rules, now)
      }
    }
  }

  private fun step(
    start: RulePhase,
    facts: RuleFacts,
    rules: List<MitigationRule>,
    now: Long,
  ): Pair<RulePhase, RuleChange?> {
    val (pick, picked) = pick(start, facts, rules, now)
    var phase = picked
    var change: RuleChange? = null
    if (pick !== phase.matched) {
      val from = phase.matched
      phase =
        phase.copy(
          matched = pick,
          matchedSinceMillis = now,
          onsetAtMillis = now + delayFor(from, pick),
          holdUntilMillis = now + (pick?.timing?.holdMillis ?: 0L),
        )
      change = RuleChange(from?.id, pick?.id, reasonFor(from, pick))
    }
    phase = settle(phase, facts, now)
    return phase.copy(activeEffects = phase.applied?.effects?.resolve(facts) ?: emptyMap()) to
      change
  }

  private fun pick(
    phase: RulePhase,
    facts: RuleFacts,
    rules: List<MitigationRule>,
    now: Long,
  ): Pair<MitigationRule?, RulePhase> {
    val p = if (phase.spent?.matches(facts) == false) phase.copy(spent = null) else phase
    val best = rules.firstOrNull { it.matches(facts) && it !== p.spent }
    val current = p.matched ?: return best to p

    if (best != null && best.order < current.order) return best to p
    if (expired(p, current, facts, now))
      return best.takeIf { it !== current } to p.copy(spent = current)
    if (!current.releases(facts)) return current to p
    if (now < p.holdUntilMillis) return current to p
    return best to p
  }

  private fun expired(
    phase: RulePhase,
    current: MitigationRule,
    facts: RuleFacts,
    now: Long,
  ): Boolean {
    if (phase.appliedAtMillis == 0L) return false
    val span = current.timing.maxMillis
    val windows = current.timing.maxAnswers
    return (span > 0L && now - phase.appliedAtMillis >= span) ||
      (windows > 0L && facts.answers - phase.answersAtApply >= windows)
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

  private fun settle(phase: RulePhase, facts: RuleFacts, now: Long): RulePhase {
    if (phase.applied === phase.matched || now < phase.onsetAtMillis) return phase
    val target = phase.matched
    if (target != null && !target.timing.startsInCombat && facts.inCombat) return phase
    return phase.copy(
      applied = target,
      appliedAtMillis = if (target == null) 0L else now,
      answersAtApply = facts.answers,
    )
  }

  private fun release(phase: RulePhase, now: Long, reason: String): Pair<RulePhase, RuleChange?> {
    if (phase.matched == null && phase.applied == null) return phase to null
    val from = phase.matched ?: phase.applied
    val stopped =
      phase.copy(
        matched = null,
        applied = null,
        onsetAtMillis = now,
        holdUntilMillis = now,
        appliedAtMillis = 0L,
      )
    return stopped to RuleChange(from?.id, null, reason)
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
