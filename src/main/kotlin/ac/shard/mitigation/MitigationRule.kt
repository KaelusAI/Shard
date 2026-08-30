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

data class RuleTiming(
  val delayMinMillis: Long,
  val delayMaxMillis: Long,
  val startsInCombat: Boolean,
  val holdMillis: Long,
  val releaseJitterMaxMillis: Long,
  val maxMillis: Long = 0L,
  val maxAnswers: Long = 0L,
)

sealed interface RuleEffects {

  val channels: Set<String>

  fun resolve(facts: RuleFacts): Map<String, Double>

  data class Flat(val values: Map<String, Double>) : RuleEffects {
    override val channels: Set<String> = values.keys

    override fun resolve(facts: RuleFacts): Map<String, Double> = values
  }

  data class Scale(
    val fact: Fact,
    val from: Double,
    val to: Double,
    val ranges: Map<String, Pair<Double, Double>>,
    val label: String? = null,
  ) : RuleEffects {
    override val channels: Set<String> = ranges.keys

    override fun resolve(facts: RuleFacts): Map<String, Double> {
      val span = to - from
      val reading = fact.read(facts, label)
      val share = if (span == 0.0) 1.0 else ((reading - from) / span).coerceIn(0.0, 1.0)
      return ranges.mapValues { (_, pair) -> pair.first + (pair.second - pair.first) * share }
    }
  }
}

data class MitigationRule(
  val id: String,
  val order: Int,
  val level: MitigationTier,
  val enabled: Boolean,
  val entry: RuleCondition,
  val until: RuleCondition?,
  val effects: RuleEffects,
  val timing: RuleTiming,
) {
  fun matches(facts: RuleFacts): Boolean = enabled && entry.holds(facts)

  fun releases(facts: RuleFacts): Boolean =
    if (until != null) until.holds(facts) else !entry.holds(facts)
}
