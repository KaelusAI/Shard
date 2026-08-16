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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MitigationsFileTest {

  private fun parse(yaml: String, complaints: MutableList<String> = mutableListOf()) =
    MitigationsFile.read(
      YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build().load(),
      complaints,
    )

  private fun MitigationRule.flat(): Map<String, Double> = (effects as RuleEffects.Flat).values

  private fun shipped(complaints: MutableList<String> = mutableListOf()): MitigationSettings {
    val stream =
      this::class.java.classLoader.getResourceAsStream("mitigations.yml")
        ?: error("bundled mitigations.yml is missing from the test classpath")
    return MitigationsFile.read(
      YamlConfigurationLoader.builder().source { stream.bufferedReader() }.build().load(),
      complaints,
    )
  }

  @Test
  fun `the shipped file parses without a single complaint`() {
    val complaints = mutableListOf<String>()
    val settings = shipped(complaints)

    assertEquals(emptyList(), complaints)
    assertTrue(settings.enabled)
    assertEquals(
      listOf("blatant", "strong", "sustained", "watching", "tax"),
      settings.rules.map { it.id },
      "strongest first: order in the file is what lets a rule cut in on a weaker one",
    )
  }

  @Test
  fun `the score rule asks about the score and the sample, and nothing about the player`() {
    val sustained = shipped().rule("sustained")

    fun facts(score: Double, days: Int) =
      RuleFacts(
        score = score,
        buffer = 0.0,
        probability = 0.0,
        answers = 400L,
        sessions = 1,
        days = days,
        onlineMillis = 0L,
        inCombat = true,
      )

    assertTrue(
      sustained!!.matches(facts(score = 30.0, days = 0)),
      "buying a fresh account must not be the way around this rule",
    )
    assertTrue(
      sustained.matches(facts(score = 30.0, days = 30)),
      "having been mitigated before must not buy anyone extra room either",
    )
    assertFalse(sustained.matches(facts(score = 12.0, days = 30)), "the score is what decides")
    assertFalse(sustained.matches(facts(score = 30.0, days = 0).copy(answers = 50L)))
  }

  @Test
  fun `the shipped rules sit on the tiers the API speaks`() {
    val settings = shipped()

    assertEquals(MitigationTier.HIGH, settings.rule("blatant")?.level)
    assertEquals(MitigationTier.MID, settings.rule("strong")?.level)
    assertEquals(MitigationTier.MID, settings.rule("sustained")?.level)
    assertEquals(MitigationTier.LOW, settings.rule("watching")?.level)
  }

  @Test
  fun `the strictest rule carries its own proof and waits for no global gate`() {
    val settings = shipped()

    assertTrue(
      settings.score.minAnswers <= 10,
      "the global gate switches off every rule at once, so it must stay a formality",
    )
    fun asksForSample(rule: MitigationRule?): Boolean {
      val facts = mutableListOf<Fact>()
      fun walk(condition: RuleCondition) {
        when (condition) {
          is RuleCondition.Threshold -> facts += condition.fact
          is RuleCondition.All -> condition.parts.forEach(::walk)
          is RuleCondition.Any -> condition.parts.forEach(::walk)
          is RuleCondition.Not -> walk(condition.part)
          RuleCondition.Always -> Unit
        }
      }
      rule?.entry?.let(::walk)
      return Fact.ANSWERS in facts
    }

    assertTrue(
      !asksForSample(settings.rule("blatant")),
      "six unbroken seconds plus a loaded buffer is already stronger proof than any head count",
    )
    assertTrue(asksForSample(settings.rule("strong")), "a weaker signal asks for a sample itself")
    assertTrue(asksForSample(settings.rule("sustained")))
  }

  @Test
  fun `the strictest rule also opens on a score that walking away cannot reset`() {
    val blatant = shipped().rule("blatant")!!

    fun facts(score: Double, probability: Double, buffer: Double, holds: Long) =
      RuleFacts(
        score = score,
        buffer = buffer,
        probability = probability,
        answers = 400L,
        sessions = 1,
        days = 1,
        onlineMillis = 600_000L,
        inCombat = true,
        probabilityHolds = mapOf(HoldKey(0.90, 6_000L) to holds),
      )

    assertTrue(
      blatant.matches(facts(score = 34.0, probability = 0.0, buffer = 0.0, holds = 0L)),
      "a score that high was earned over minutes, so a pause must not buy a fresh six seconds",
    )
    assertTrue(
      blatant.matches(facts(score = 0.0, probability = 0.99, buffer = 40.0, holds = 9_000L)),
      "the live door still works on its own",
    )
    assertFalse(
      blatant.matches(facts(score = 20.0, probability = 0.99, buffer = 10.0, holds = 9_000L)),
      "an unloaded buffer means the model is parked just over the line, not really hitting",
    )
    assertFalse(
      blatant.releases(facts(score = 30.0, probability = 0.0, buffer = 0.0, holds = 0L)),
      "leaving the fight must not end it while the score is still up there",
    )
    assertTrue(
      blatant.releases(facts(score = 20.0, probability = 0.0, buffer = 0.0, holds = 0L)),
      "both signals gone, so is the rule",
    )
  }

  @Test
  fun `the shipped score settings sit where the model actually answers`() {
    val score = shipped().score

    assertTrue(
      score.clampHigh <= 0.97,
      "the model is trained with a smoothed cheat label and never answers above 0.97",
    )
    assertTrue(
      score.neutral <= 0.20,
      "measured: above this a tenth of honest sessions start reaching the rule thresholds",
    )

    fun windowScore(probability: Double) =
      ScoreMath.contribution(probability, step = 10, sequence = 40, settings = score)

    assertTrue(
      windowScore(0.018) < 0.0,
      "the median honest window has to remove suspicion, not add it",
    )
    assertTrue(
      windowScore(0.893) > 0.8,
      "a soft aim assist sits at 0.893 and must still climb at a useful pace",
    )
  }

  @Test
  fun `the standing toll sits last and never takes a player from a real rule`() {
    val settings = shipped()
    val tax = settings.rule("tax")!!

    assertEquals(settings.rules.size - 1, tax.order, "first match wins, so this one goes last")
    assertNull(tax.until, "no hysteresis: it rides the last answer and lets go on the next")
    assertEquals(0L, tax.timing.holdMillis)
    assertEquals(0L, tax.timing.maxAnswers, "a ceiling would stop it re-arming on the next window")
    assertEquals(MitigationTier.LOW, tax.level, "staff must not be paged for half the server")

    val effects = tax.effects as RuleEffects.Scale
    val atEntry = effects.resolve(RuleFacts(0.0, 0.0, 0.90, 0L, 0, 0, 0L, true))
    val atCeiling = effects.resolve(RuleFacts(0.0, 0.0, 0.96, 0L, 0, 0, 0L, true))
    assertTrue(atEntry.getValue(MitigationSettings.MELEE) > 0.9, "one honest window must not sting")
    assertTrue(
      atCeiling.getValue(MitigationSettings.MELEE) < atEntry.getValue(MitigationSettings.MELEE)
    )
  }

  @Test
  fun `the file collects every probability threshold the rules ask about`() {
    assertEquals(setOf(0.90, 0.80), shipped().probabilityThresholds)
  }

  @Test
  fun `the strongest rule sits first and touches no gameplay at the weakest`() {
    val settings = shipped()

    assertEquals(0, settings.rule("blatant")?.order)
    assertEquals(
      emptyMap(),
      settings.rule("watching")?.flat(),
      "the level reached in one session must stay invisible to the player",
    )
  }

  @Test
  fun `a rule whose level is not one of the four is dropped with a complaint`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: ghost
            level: nope
            when: { score: { above: 1.0 } }
        """
          .trimIndent(),
        complaints,
      )

    assertEquals(emptyList(), settings.rules)
    assertTrue(complaints.any { it.contains("ghost") })
  }

  @Test
  fun `a condition about something the rules cannot see is refused`() {
    val complaints = mutableListOf<String>()
    parse(
      """
      enabled: true
      rules:
        - id: odd
          level: high
          when: { moon-phase: { above: 1.0 } }
      """
        .trimIndent(),
      complaints,
    )

    assertTrue(complaints.any { it.contains("moon-phase") })
  }

  @Test
  fun `all and any nest and both are read`() {
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: mixed
            level: mid
            when:
              all:
                - score: { above: 10.0 }
                - any:
                    - buffer: { above: 30.0 }
                    - probability: { above: 0.9, for-seconds: 4 }
            then: { melee: 0.5 }
        """
          .trimIndent()
      )

    val rule = settings.rule("mixed")
    assertContains(settings.probabilityThresholds, 0.9)
    assertTrue(rule!!.entry is RuleCondition.All)

    val base =
      RuleFacts(
        score = 12.0,
        buffer = 0.0,
        probability = 0.0,
        answers = 500,
        sessions = 1,
        days = 1,
        onlineMillis = 600_000,
        inCombat = false,
      )
    assertTrue(!rule.matches(base), "neither branch of the any holds")
    assertTrue(rule.matches(base.copy(buffer = 40.0)))
    assertTrue(
      rule.matches(
        base.copy(probability = 0.95, probabilityHolds = mapOf(HoldKey(0.9, 4_000L) to 9_000L))
      )
    )
  }

  @Test
  fun `incoming damage rides only on the rules that need no false-positive budget`() {
    val settings = shipped()

    val blatant = settings.rule("blatant")!!.flat()
    val strong = settings.rule("strong")!!.flat()

    assertTrue(
      blatant.getValue(MitigationSettings.INCOMING) > strong.getValue(MitigationSettings.INCOMING),
      "the rule that proves more may take more",
    )
    assertTrue(
      blatant.getValue(MitigationSettings.HEALING) < strong.getValue(MitigationSettings.HEALING)
    )
    assertTrue(
      blatant.getValue(MitigationSettings.MELEE) < strong.getValue(MitigationSettings.MELEE)
    )
    assertNull(settings.rule("sustained")?.flat()?.get(MitigationSettings.HEALING))
    assertTrue(blatant.getValue(MitigationSettings.CANCEL) > 0.0)
    assertTrue(
      blatant.getValue(MitigationSettings.CANCEL) > strong.getValue(MitigationSettings.CANCEL),
      "a miss is the loudest channel, so the rule that proves less uses it more sparingly",
    )
    assertNull(settings.rule("sustained")?.flat()?.get(MitigationSettings.CANCEL))
    assertNull(
      settings.rule("sustained")?.flat()?.get(MitigationSettings.INCOMING),
      "the half-bypass rule leans on a weak per-window signal and must not raise damage taken",
    )
    assertNull(settings.rule("watching")?.flat()?.get(MitigationSettings.INCOMING))
  }

  @Test
  fun `outgoing is clamped under one and incoming over it`() {
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: silly
            level: high
            when: { score: { above: 1.0 } }
            then: { melee: 4.0, incoming: 0.2 }
        """
          .trimIndent()
      )

    val effects = settings.rule("silly")!!.flat()
    assertEquals(1.0, effects[MitigationSettings.MELEE], "dealing more damage is not a mitigation")
    assertEquals(
      1.0,
      effects[MitigationSettings.INCOMING],
      "taking less damage is not a mitigation either",
    )
  }

  @Test
  fun `an unknown channel is refused rather than silently scaling nothing`() {
    val complaints = mutableListOf<String>()
    parse(
      """
      enabled: true
      rules:
        - id: odd
          level: high
          when: { score: { above: 1.0 } }
          then: { knockback: 0.5 }
      """
        .trimIndent(),
      complaints,
    )

    assertTrue(complaints.any { it.contains("knockback") })
  }
}
