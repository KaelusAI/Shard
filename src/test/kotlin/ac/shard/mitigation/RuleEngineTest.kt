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

import ac.shard.config.MitigationsFile
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RuleEngineTest {

  private val scoring = MitigationsFile.DEFAULT_SCORE

  @Suppress("LongParameterList")
  private fun rule(
    id: String,
    order: Int,
    level: MitigationTier,
    entry: RuleCondition,
    until: RuleCondition? = null,
    melee: Double = 0.5,
    delay: Long = 0L,
    inCombat: Boolean = true,
    hold: Long = 0L,
    max: Long = 0L,
    maxAnswers: Long = 0L,
  ) =
    MitigationRule(
      id = id,
      order = order,
      level = level,
      enabled = true,
      entry = entry,
      until = until,
      effects = RuleEffects.Flat(mapOf(MitigationSettings.MELEE to melee)),
      timing =
        RuleTiming(
          delayMinMillis = delay,
          delayMaxMillis = delay,
          startsInCombat = inCombat,
          holdMillis = hold,
          releaseJitterMaxMillis = 0L,
          maxMillis = max,
          maxAnswers = maxAnswers,
        ),
    )

  private fun above(fact: Fact, value: Double) = RuleCondition.Threshold(fact, above = value)

  private fun below(fact: Fact, value: Double) = RuleCondition.Threshold(fact, below = value)

  private fun settings(vararg rules: MitigationRule) =
    MitigationSettings(
      enabled = true,
      score = scoring,
      skip = SkipSettings(bedrock = true, followAiRegions = true),
      rules = rules.toList(),
    )

  private class Ticker(var now: Long = 1_775_000_000_000L)

  @Suppress("LongParameterList")
  private fun facts(
    score: Double = 0.0,
    probability: Double = 0.0,
    buffer: Double = 0.0,
    inCombat: Boolean = false,
    holds: Map<HoldKey, Long> = emptyMap(),
    answers: Long = 1_000,
  ) =
    RuleFacts(
      score = score,
      buffer = buffer,
      probability = probability,
      answers = answers,
      sessions = 5,
      days = 5,
      onlineMillis = 600_000,
      inCombat = inCombat,
      probabilityHolds = holds,
    )

  @Test
  fun `a scaled rule follows the fact instead of holding one number`() {
    val ticker = Ticker()
    val sliding =
      MitigationRule(
        id = "sliding",
        order = 0,
        level = MitigationTier.MID,
        enabled = true,
        entry = above(Fact.PROBABILITY, 0.90),
        until = null,
        effects =
          RuleEffects.Scale(
            Fact.PROBABILITY,
            from = 0.90,
            to = 1.0,
            ranges = mapOf(MitigationSettings.MELEE to (1.0 to 0.0)),
          ),
        timing = RuleTiming(0L, 0L, true, 0L, 0L),
      )
    val engine = RuleEngine({ settings(sliding) }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(probability = 0.95, inCombat = true), null)
    assertEquals(0.5, state.multiplierFor(MitigationSettings.MELEE), 1e-9)

    ticker.now += 1_000L
    engine.evaluate(state, facts(probability = 0.99, inCombat = true), null)
    assertEquals(0.1, state.multiplierFor(MitigationSettings.MELEE), 1e-9)

    ticker.now += 1_000L
    engine.evaluate(state, facts(probability = 0.40, inCombat = true), null)
    assertEquals(
      1.0,
      state.multiplierFor(MitigationSettings.MELEE),
      1e-9,
    )
  }

  @Test
  fun `a rule with a deadline ends on time even while its hold still runs`() {
    val ticker = Ticker()
    val config =
      settings(
        rule(
          "timed",
          0,
          MitigationTier.MID,
          above(Fact.PROBABILITY, 0.90),
          until = below(Fact.PROBABILITY, 0.50),
          hold = 60_000L,
          max = 10_000L,
        )
      )
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(probability = 0.99, inCombat = true), null)
    assertEquals("timed", state.applied?.id)

    ticker.now += 9_000L
    engine.evaluate(state, facts(probability = 0.99, inCombat = true), null)
    assertEquals("timed", state.applied?.id, "the deadline has not come round yet")

    ticker.now += 1_500L
    engine.evaluate(state, facts(probability = 0.99, inCombat = true), null)
    assertNull(
      state.applied,
      "the deadline outranks both the hold and the still-matching entry condition",
    )
  }

  @Test
  fun `a rule can be told to end at the next answer from the model`() {
    val ticker = Ticker()
    val config =
      settings(
        rule("one-window", 0, MitigationTier.MID, above(Fact.PROBABILITY, 0.95), maxAnswers = 1L)
      )
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(probability = 0.99, inCombat = true, answers = 500), null)
    assertEquals("one-window", state.applied?.id)

    ticker.now += 1_000L
    engine.evaluate(state, facts(probability = 0.99, inCombat = true, answers = 501), null)
    assertNull(state.applied, "one answer arrived, so the rule is done")

    ticker.now += 1_000L
    engine.evaluate(state, facts(probability = 0.99, inCombat = true, answers = 502), null)
    assertNull(state.applied, "and it must not switch itself straight back on")

    ticker.now += 1_000L
    engine.evaluate(state, facts(probability = 0.20, inCombat = true, answers = 503), null)
    ticker.now += 1_000L
    engine.evaluate(state, facts(probability = 0.99, inCombat = true, answers = 504), null)
    assertEquals("one-window", state.applied?.id, "once the entry lets go, the rule may run again")
  }

  @Test
  fun `the first rule that matches wins, and a stronger one cuts in at once`() {
    val ticker = Ticker()
    val config =
      settings(
        rule("blatant", 0, MitigationTier.HIGH, above(Fact.PROBABILITY, 0.95), melee = 0.4),
        rule("sustained", 1, MitigationTier.MID, above(Fact.SCORE, 16.0), melee = 0.75),
      )
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(score = 20.0), null)
    assertEquals("sustained", state.applied?.id)

    engine.evaluate(state, facts(score = 20.0, probability = 0.99, inCombat = true), null)
    assertEquals("blatant", state.applied?.id, "a rule above may take over mid-fight")
  }

  @Test
  fun `a weaker rule waits for the running one to release`() {
    val ticker = Ticker()
    val config =
      settings(
        rule(
          "blatant",
          0,
          MitigationTier.HIGH,
          above(Fact.PROBABILITY, 0.95),
          until = below(Fact.PROBABILITY, 0.75),
        ),
        rule("sustained", 1, MitigationTier.MID, above(Fact.SCORE, 16.0)),
      )
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(probability = 0.99, score = 20.0), null)
    assertEquals("blatant", state.applied?.id)

    engine.evaluate(state, facts(probability = 0.85, score = 20.0), null)
    assertEquals(
      "blatant",
      state.applied?.id,
      "0.85 is under the entry but over the release, so it holds",
    )

    engine.evaluate(state, facts(probability = 0.5, score = 20.0), null)
    assertEquals("sustained", state.applied?.id)
  }

  @Test
  fun `hold keeps a rule running after its release condition is met`() {
    val ticker = Ticker()
    val config =
      settings(rule("nerf", 0, MitigationTier.MID, above(Fact.SCORE, 16.0), hold = 60_000L))
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(score = 20.0), null)
    assertEquals("nerf", state.applied?.id)

    ticker.now += 10_000
    engine.evaluate(state, facts(score = 0.0), null)
    assertEquals("nerf", state.applied?.id, "the hold has not run out")

    ticker.now += 60_000
    engine.evaluate(state, facts(score = 0.0), null)
    assertNull(state.applied)
  }

  @Test
  fun `a rule that may not start in combat waits for the fight to end`() {
    val ticker = Ticker()
    val config =
      settings(rule("nerf", 0, MitigationTier.MID, above(Fact.SCORE, 16.0), inCombat = false))
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(score = 20.0, inCombat = true), null)
    assertEquals("nerf", state.matched?.id)
    assertNull(state.applied, "matched, but not applied while the fight is on")

    engine.evaluate(state, facts(score = 20.0, inCombat = false), null)
    assertEquals("nerf", state.applied?.id)
  }

  @Test
  fun `the delay holds the effect back even once the rule matched`() {
    val ticker = Ticker()
    val config =
      settings(rule("nerf", 0, MitigationTier.MID, above(Fact.SCORE, 16.0), delay = 45_000L))
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(score = 20.0), null)
    assertNull(state.applied)

    ticker.now += 50_000
    engine.evaluate(state, facts(score = 20.0), null)
    assertEquals("nerf", state.applied?.id)
  }

  @Test
  fun `a skipped player is released at once, whatever is running`() {
    val ticker = Ticker()
    val config =
      settings(rule("nerf", 0, MitigationTier.MID, above(Fact.SCORE, 16.0), hold = 1e6.toLong()))
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(state, facts(score = 20.0), null)
    assertEquals("nerf", state.applied?.id)

    engine.evaluate(state, facts(score = 20.0, inCombat = true), SkipReason.EXEMPT)

    assertNull(state.applied, "an exemption beats the hold and the fight")
    assertNull(state.matched)
  }

  @Test
  fun `probability for-seconds only counts once it has been held that long`() {
    val ticker = Ticker()
    val condition = RuleCondition.Threshold(Fact.PROBABILITY, above = 0.95, heldMillis = 6_000L)
    val config = settings(rule("blatant", 0, MitigationTier.HIGH, condition))
    val engine = RuleEngine({ config }, { ticker.now }, Random(1))
    val state = MitigationState()

    engine.evaluate(
      state,
      facts(probability = 0.99, holds = mapOf(HoldKey(0.95, 6_000L) to 2_000L)),
      null,
    )
    assertNull(state.applied, "high for two seconds is a spike, not a pattern")

    engine.evaluate(
      state,
      facts(probability = 0.99, holds = mapOf(HoldKey(0.95, 6_000L) to 8_000L)),
      null,
    )
    assertEquals("blatant", state.applied?.id)
  }
}
