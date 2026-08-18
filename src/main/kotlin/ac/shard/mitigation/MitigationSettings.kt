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

data class SkipSettings(val bedrock: Boolean, val followAiRegions: Boolean)

data class MitigationSettings(
  val enabled: Boolean,
  val logEnabled: Boolean,
  val score: ScoreSettings,
  val skip: SkipSettings,
  val rules: List<MitigationRule>,
) {
  val probabilityHolds: Set<HoldKey> = rules.flatMapTo(mutableSetOf()) { thresholdsIn(it) }

  val probabilityThresholds: Set<Double> = probabilityHolds.mapTo(mutableSetOf()) { it.threshold }

  fun rule(id: String): MitigationRule? = rules.firstOrNull { it.id == id }

  private fun thresholdsIn(rule: MitigationRule): Set<HoldKey> {
    val found = mutableSetOf<HoldKey>()
    collect(rule.entry, found)
    rule.until?.let { collect(it, found) }
    return found
  }

  private fun collect(condition: RuleCondition, into: MutableSet<HoldKey>) {
    when (condition) {
      is RuleCondition.Threshold ->
        if (
          condition.fact == Fact.PROBABILITY && condition.above != null && condition.heldMillis > 0L
        ) {
          into += HoldKey(condition.above, condition.heldMillis)
        }
      is RuleCondition.All -> condition.parts.forEach { collect(it, into) }
      is RuleCondition.Any -> condition.parts.forEach { collect(it, into) }
      is RuleCondition.Not -> collect(condition.part, into)
      RuleCondition.Always -> Unit
    }
  }

  companion object {
    const val MELEE = "melee"
    const val PROJECTILE = "projectile"
    const val CRYSTAL = "crystal"
    const val INCOMING = "incoming"
    const val HEALING = "healing"
    const val CANCEL = "cancel"

    const val MAX_INCOMING = 4.0

    val OUTGOING = setOf(MELEE, PROJECTILE, CRYSTAL)
    val CHANNELS = OUTGOING + HEALING + INCOMING + CANCEL
  }
}
