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

import java.util.Locale

data class HoldAccounting(val keys: Set<HoldKey>, val coverMillis: Long, val forgetRate: Double)

data class RulePhase(
  val matched: MitigationRule? = null,
  val applied: MitigationRule? = null,
  val matchedSinceMillis: Long = 0L,
  val onsetAtMillis: Long = 0L,
  val holdUntilMillis: Long = 0L,
  val appliedAtMillis: Long = 0L,
  val peakScore: Double = 0.0,
  val answersAtApply: Long = 0L,
  val spent: MitigationRule? = null,
  val activeEffects: Map<EffectChannel, Double> = emptyMap(),
)

data class ScoreHistory(val sessions: Int, val days: Int, val lastDay: Long) {
  companion object {
    val EMPTY = ScoreHistory(0, 0, 0L)
  }
}

data class WindowShape(val low: Long, val middle: Long, val high: Long) {
  val total: Long
    get() = low + middle + high

  companion object {
    fun of(bins: IntArray): WindowShape {
      val perBin = 1.0 / bins.size
      var low = 0L
      var high = 0L
      var total = 0L
      bins.forEachIndexed { index, count ->
        total += count
        when {
          (index + 1) * perBin <= ScoreMath.LOW_TAIL_UNTIL -> low += count
          index * perBin >= ScoreMath.SPIKE_FROM -> high += count
        }
      }
      return WindowShape(low, total - low - high, high)
    }
  }
}

@Suppress("TooManyFunctions")
class MitigationState {
  private var scoreCleared = false

  @Volatile
  var score: Double = 0.0
    private set

  @Volatile
  var answers: Long = 0L
    private set

  @Volatile
  var phase: RulePhase = RulePhase()
    private set

  val matched: MitigationRule?
    get() = phase.matched

  val applied: MitigationRule?
    get() = phase.applied

  val matchedSinceMillis: Long
    get() = phase.matchedSinceMillis

  val onsetAtMillis: Long
    get() = phase.onsetAtMillis

  val appliedAtMillis: Long
    get() = phase.appliedAtMillis

  val peakScore: Double
    get() = phase.peakScore

  val spent: MitigationRule?
    get() = phase.spent

  val activeEffects: Map<EffectChannel, Double>
    get() = phase.activeEffects

  @Volatile var lastAnswerAtMillis: Long = 0L

  @Volatile var history: ScoreHistory = ScoreHistory.EMPTY

  @Volatile var countedThisSession: Boolean = false

  @Volatile private var frozen: Boolean = false

  private var lastLeakMillis: Long = 0L
  private val histogram = IntArray(ScoreMath.HISTOGRAM_BINS)
  private val held = HashMap<HoldKey, Long>()

  @Synchronized
  fun <T> transition(change: (RulePhase) -> Pair<RulePhase, T>): T {
    val (next, result) = change(phase)
    phase = next
    return result
  }

  @Synchronized
  fun updatePhase(change: (RulePhase) -> RulePhase) {
    phase = change(phase)
  }

  val appliedTier: MitigationTier
    get() = applied?.level ?: MitigationTier.NONE

  val matchedTier: MitigationTier
    get() = matched?.level ?: MitigationTier.NONE

  val tierName: String
    get() = appliedTier.name.lowercase(Locale.US)

  val effects: Set<EffectChannel>
    get() = applied?.effects?.channels ?: emptySet()

  fun multiplierFor(channel: EffectChannel): Double = activeEffects[channel] ?: 1.0

  fun chanceFor(channel: EffectChannel): Double = activeEffects[channel] ?: 0.0

  @Synchronized
  fun record(
    contribution: Double,
    probability: Double,
    now: Long,
    settings: ScoreSettings,
    answered: Boolean = true,
  ) {
    leak(now, settings)
    score = ScoreMath.clampToRange(score + contribution, settings)
    if (!answered) return
    answers++
    histogram[ScoreMath.bin(probability)]++
  }

  @Synchronized
  fun forgetProbability() {
    held.clear()
  }

  @Synchronized
  fun noteProbability(
    probability: Double,
    now: Long,
    holds: HoldAccounting,
    labels: Map<String, Double> = emptyMap(),
  ) {
    val gap = if (lastAnswerAtMillis == 0L) holds.coverMillis else (now - lastAnswerAtMillis)
    val seen = gap.coerceIn(0L, holds.coverMillis)
    val unseen = (gap - seen).coerceAtLeast(0L)
    lastAnswerAtMillis = now

    holds.keys.forEach { key ->
      val reading = if (key.label == null) probability else labels[key.label] ?: 0.0
      if (reading < key.threshold) {
        held.remove(key)
        return@forEach
      }
      val eroded = (held[key] ?: 0L) - (unseen * holds.forgetRate).toLong()
      held[key] = (eroded + seen).coerceIn(0L, key.requiredMillis)
    }
    held.keys.retainAll(holds.keys)
  }

  @Synchronized fun probabilityHolds(): Map<HoldKey, Long> = held.toMap()

  @Synchronized
  fun leak(now: Long, settings: ScoreSettings) {
    if (lastLeakMillis == 0L || now <= lastLeakMillis || frozen) {
      lastLeakMillis = now
      return
    }
    score =
      ScoreMath.clampToRange(
        ScoreMath.decay(score, now - lastLeakMillis, settings.halfLifeMillis),
        settings,
      )
    lastLeakMillis = now
  }

  @Synchronized
  fun freeze(now: Long, settings: ScoreSettings) {
    if (frozen) return
    leak(now, settings)
    frozen = true
  }

  @Synchronized
  fun thaw(now: Long) {
    if (!frozen) return
    frozen = false
    lastLeakMillis = now
  }

  fun isFrozen(): Boolean = frozen

  @Synchronized
  fun restore(value: Double, now: Long, settings: ScoreSettings) {
    if (scoreCleared) return
    score = ScoreMath.clampToRange(value.coerceAtMost(settings.capOnRestore), settings)
    lastLeakMillis = now
  }

  @Synchronized fun histogramSnapshot(): IntArray = histogram.copyOf()

  fun shape(): WindowShape = WindowShape.of(histogramSnapshot())

  @Synchronized
  fun clearScore(now: Long) {
    scoreCleared = true
    score = 0.0
    lastLeakMillis = now
    held.clear()
  }
}
