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

data class HoldKey(val threshold: Double, val requiredMillis: Long)

data class RuleFacts(
  val score: Double,
  val buffer: Double,
  val probability: Double,
  val answers: Long,
  val sessions: Int,
  val days: Int,
  val onlineMillis: Long,
  val inCombat: Boolean,
  val probabilityHolds: Map<HoldKey, Long> = emptyMap(),
)

enum class Fact(val key: String) {
  SCORE("score"),
  BUFFER("buffer"),
  PROBABILITY("probability"),
  ANSWERS("answers"),
  SESSIONS("mitigated-sessions"),
  DAYS("mitigated-days"),
  ONLINE_SECONDS("online-seconds");

  fun read(facts: RuleFacts): Double =
    when (this) {
      SCORE -> facts.score
      BUFFER -> facts.buffer
      PROBABILITY -> facts.probability
      ANSWERS -> facts.answers.toDouble()
      SESSIONS -> facts.sessions.toDouble()
      DAYS -> facts.days.toDouble()
      ONLINE_SECONDS -> facts.onlineMillis / MILLIS_PER_SECOND
    }

  companion object {
    private const val MILLIS_PER_SECOND = 1000.0
    private val BY_KEY = entries.associateBy { it.key }

    fun of(key: String?): Fact? = BY_KEY[key?.trim()?.lowercase()]
  }
}

sealed interface RuleCondition {
  fun holds(facts: RuleFacts): Boolean

  data object Always : RuleCondition {
    override fun holds(facts: RuleFacts) = true
  }

  data class Threshold(
    val fact: Fact,
    val above: Double? = null,
    val below: Double? = null,
    val heldMillis: Long = 0L,
  ) : RuleCondition {
    override fun holds(facts: RuleFacts): Boolean {
      val value = fact.read(facts)
      if (above != null && value < above) return false
      if (below != null && value > below) return false
      if (heldMillis <= 0L) return true
      if (fact != Fact.PROBABILITY || above == null) return true
      return (facts.probabilityHolds[HoldKey(above, heldMillis)] ?: 0L) >= heldMillis
    }
  }

  data class All(val parts: List<RuleCondition>) : RuleCondition {
    override fun holds(facts: RuleFacts) = parts.all { it.holds(facts) }
  }

  data class Any(val parts: List<RuleCondition>) : RuleCondition {
    override fun holds(facts: RuleFacts) = parts.any { it.holds(facts) }
  }

  data class Not(val part: RuleCondition) : RuleCondition {
    override fun holds(facts: RuleFacts) = !part.holds(facts)
  }
}
