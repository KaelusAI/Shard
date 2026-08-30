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

import ac.shard.Shard
import ac.shard.ai.label.LabelKey
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigManager
import ac.shard.config.MitigationsFile
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MitigationLabelRulesTest {

  @Test
  fun `a rule sees every label's buffer, not only the loudest one`() {
    val facts = factsWith(buffers = mapOf("aim" to 31.0, "trigger" to 24.0), probabilities = null)

    assertEquals(mapOf("aim" to 31.0, "trigger" to 24.0), facts.labelBuffers)
    assertEquals(31.0, Fact.BUFFER.read(facts, label = "aim"), 1e-9)
    assertEquals(55.0, Fact.BUFFER.read(facts, fold = Fold.SUM), 1e-9)
    assertEquals(
      31.0,
      Fact.BUFFER.read(facts),
      1e-9,
      "max stays the default so a file written before labels means what it always did",
    )
  }

  @Test
  fun `a model gone quiet stops feeding label probabilities the way it stops feeding the one`() {
    val fresh = factsWith(probabilities = mapOf("aim" to 0.97), heardMillisAgo = 500L)
    val stale = factsWith(probabilities = mapOf("aim" to 0.97), heardMillisAgo = 30_000L)

    assertEquals(0.97, Fact.PROBABILITY.read(fresh, label = "aim"), 1e-9)
    assertEquals(
      0.0,
      Fact.PROBABILITY.read(stale, label = "aim"),
      1e-9,
      "a verdict left on a volatile field is not a reading; a rule on it would never let go",
    )
    assertEquals(
      mapOf("aim" to 12.0),
      stale.labelBuffers,
      "buffers are state the check owns and keeps draining, not a reading that goes stale",
    )
  }

  @Test
  fun `a label the model never declared reads zero, and below still matches on it`() {
    val facts = factsWith(buffers = mapOf("aim" to 30.0), probabilities = null)
    val absent = RuleCondition.Threshold(Fact.BUFFER, below = 5.0, label = "reach")

    assertEquals(0.0, Fact.BUFFER.read(facts, label = "reach"), 1e-9)
    assertTrue(
      absent.holds(facts),
      "a label with no buffer is a label at zero, the same way the whole buffer reads zero",
    )
  }

  @Test
  fun `a label picks one buffer out where a fold adds them all up`() {
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: one-mechanic
            level: high
            when:
              buffer: { label: aim, above: 25.0 }
            then: { melee: 0.3 }
          - id: everything-at-once
            level: mid
            when:
              buffer: { fold: sum, above: 40.0 }
            then: { melee: 0.6 }
        """
      )
    val split = factsWith(buffers = mapOf("aim" to 22.0, "trigger" to 21.0), probabilities = null)
    val loud = factsWith(buffers = mapOf("aim" to 30.0), probabilities = null)

    assertFalse(
      settings.rule("one-mechanic")!!.matches(split),
      "neither mechanic is loud on its own",
    )
    assertTrue(settings.rule("everything-at-once")!!.matches(split), "together they are")
    assertTrue(settings.rule("one-mechanic")!!.matches(loud))
    assertFalse(
      settings.rule("everything-at-once")!!.matches(loud),
      "one label at 30 is 30 however it is folded",
    )
  }

  @Test
  fun `the merged buffer is not one of the labels a sum adds up`() {
    val facts =
      factsWith(
        buffers = mapOf("aim" to 22.0, LabelKey.UNATTRIBUTED to 21.0),
        probabilities = null,
      )

    assertEquals(
      22.0,
      Fact.BUFFER.read(facts, fold = Fold.SUM),
      1e-9,
      "the merged buffer holds windows the model could not attribute to a mechanic, so adding " +
        "it to the mechanic's own buffer counts the same player's same behaviour twice",
    )
  }

  @Test
  fun `a rule cannot point at a buffer this server keeps for itself`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: reserved
            level: mid
            when:
              buffer: { label: _unattributed, above: 10.0 }
            then: { melee: 0.5 }
        """,
        complaints,
      )

    assertNull(
      settings.rule("reserved"),
      "canonicalising would quietly turn it into unattributed, a name nothing ever answers to",
    )
    assertTrue(complaints.any { it.contains("keeps for itself") }, complaints.toString())
  }

  @Test
  fun `a rule opened on a sum lets go when the sum falls, not when one label does`() {
    val rule =
      parse(
          """
          enabled: true
          rules:
            - id: everything-at-once
              level: mid
              when:
                buffer: { fold: sum, above: 40.0 }
              then: { melee: 0.6 }
          """
        )
        .rule("everything-at-once")!!
    val together =
      factsWith(buffers = mapOf("aim" to 22.0, "trigger" to 21.0), probabilities = null)
    val drained = factsWith(buffers = mapOf("aim" to 22.0, "trigger" to 5.0), probabilities = null)
    val joined =
      factsWith(
        buffers = mapOf("aim" to 22.0, "trigger" to 5.0, "reach" to 20.0),
        probabilities = null,
      )

    assertTrue(rule.matches(together))
    assertTrue(rule.releases(drained), "one mechanic quiet leaves the other alone under the line")
    assertFalse(
      rule.releases(joined),
      "a head the model grew mid-session holds the rule open, which is what asking for a sum " +
        "buys: the threshold is against however many labels there are now",
    )
  }

  @Test
  fun `a label on a fact that has none is refused, not quietly ignored`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: nonsense
            level: mid
            when:
              score: { label: aim, above: 20.0 }
            then: { melee: 0.5 }
        """,
        complaints,
      )

    assertNull(
      settings.rule("nonsense"),
      "ignoring the selector would leave a rule that fires on every label at once",
    )
    assertTrue(complaints.any { it.contains("puts a label on score") }, complaints.toString())
  }

  @Test
  fun `a name an operator would write is canonicalised the way the model's names are`() {
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: spelled-out
            level: mid
            when:
              buffer: { label: "Aim Assist", above: 10.0 }
            then: { melee: 0.5 }
        """
      )
    val entry = settings.rule("spelled-out")!!.entry as RuleCondition.Threshold

    assertEquals(
      "aim_assist",
      entry.label,
      "the resolver only ever produces canonical keys, so a raw name would read zero forever",
    )
  }

  @Test
  fun `a name with nothing left after canonicalising is refused`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: junk
            level: mid
            when:
              buffer: { label: "!!!", above: 10.0 }
            then: { melee: 0.5 }
        """,
        complaints,
      )

    assertNull(settings.rule("junk"))
    assertTrue(complaints.any { it.contains("not a name a model can send") }, complaints.toString())
  }

  @Test
  fun `asking for a label and a fold at once keeps the label`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: both
            level: mid
            when:
              buffer: { label: aim, fold: sum, above: 10.0 }
            then: { melee: 0.5 }
        """,
        complaints,
      )
    val entry = settings.rule("both")!!.entry as RuleCondition.Threshold

    assertEquals("aim", entry.label)
    assertEquals(Fold.MAX, entry.fold)
    assertTrue(complaints.any { it.contains("both label and fold") }, complaints.toString())
  }

  @Test
  fun `folding probability across labels is refused, since sigmoids do not add up`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: added-up
            level: mid
            when:
              probability: { fold: sum, above: 1.2 }
            then: { melee: 0.5 }
        """,
        complaints,
      )
    val entry = settings.rule("added-up")!!.entry as RuleCondition.Threshold

    assertEquals(Fold.MAX, entry.fold)
    assertTrue(complaints.any { it.contains("only buffer can do") }, complaints.toString())
  }

  @Test
  fun `a fold nobody defined is refused instead of read as one of the two`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: mean
            level: mid
            when:
              buffer: { fold: average, above: 10.0 }
            then: { melee: 0.5 }
        """,
        complaints,
      )
    val entry = settings.rule("mean")!!.entry as RuleCondition.Threshold

    assertEquals(Fold.MAX, entry.fold)
    assertTrue(complaints.any { it.contains("max and sum") }, complaints.toString())
  }

  @Test
  fun `a scale may follow one label but never a fold`() {
    val complaints = mutableListOf<String>()
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: sliding
            level: mid
            when:
              buffer: { above: 5.0 }
            then:
              scale: { fact: buffer, label: aim, fold: sum, from: 10.0, to: 30.0, melee: [0.9, 0.2] }
        """,
        complaints,
      )
    val effects = settings.rule("sliding")!!.effects as RuleEffects.Scale
    val facts = factsWith(buffers = mapOf("aim" to 20.0, "trigger" to 40.0), probabilities = null)

    assertEquals("aim", effects.label)
    assertEquals(0.55, effects.resolve(facts)["melee"]!!, 1e-9, "halfway on aim, not on the sum")
    assertTrue(complaints.any { it.contains("on a scale") }, complaints.toString())
  }

  @Test
  fun `the same seconds asked of two labels are two separate holds`() {
    val settings =
      parse(
        """
        enabled: true
        rules:
          - id: either
            level: mid
            when:
              any:
                - probability: { label: aim, above: 0.9, for-seconds: 6 }
                - probability: { label: trigger, above: 0.9, for-seconds: 6 }
                - probability: { above: 0.9, for-seconds: 6 }
            then: { melee: 0.5 }
        """
      )

    assertEquals(
      setOf(
        HoldKey(0.9, 6_000L, "aim"),
        HoldKey(0.9, 6_000L, "trigger"),
        HoldKey(0.9, 6_000L, null),
      ),
      settings.probabilityHolds,
      "one shared counter would let two half-held labels stand in for one held one",
    )
  }

  @Test
  fun `a label the model stopped sending loses the seconds it had banked`() {
    val state = MitigationState()
    val key = HoldKey(0.9, 6_000L, "aim")
    val books = HoldAccounting(setOf(key), 2_000L, 0.5)

    state.noteProbability(0.95, 1_000L, books, mapOf("aim" to 0.95))
    state.noteProbability(0.95, 3_000L, books, mapOf("aim" to 0.95))
    assertEquals(4_000L, state.probabilityHolds()[key])

    state.noteProbability(0.95, 5_000L, books, mapOf("trigger" to 0.95))

    assertNull(
      state.probabilityHolds()[key],
      "the window said nothing about aim, and silence is not six seconds of aim",
    )
  }

  private fun parse(yaml: String, complaints: MutableList<String> = mutableListOf()) =
    MitigationsFile.read(
      YamlConfigurationLoader.builder()
        .source { yaml.trimIndent().reader().buffered() }
        .build()
        .load(),
      complaints,
    )

  private fun factsWith(
    buffers: Map<String, Double> = mapOf("aim" to 12.0),
    probabilities: Map<String, Double>? = mapOf("aim" to 0.5),
    heardMillisAgo: Long = 500L,
  ): RuleFacts {
    val now = 1_000_000L
    val state = MitigationState()
    state.noteProbability(0.5, now - heardMillisAgo, HoldAccounting(emptySet(), 1_000L, 0.5))

    val aiCheck =
      mockk<AiCheck>(relaxed = true) {
        every { labelBufferSnapshot() } returns buffers
        every { lastLabelProbabilities } returns probabilities.orEmpty()
        every { buffer } returns (buffers.values.maxOrNull() ?: 0.0)
        every { lastCheatProbability } returns (probabilities?.values?.maxOrNull() ?: 0.0)
      }

    val player =
      mockk<ShardPlayer>(relaxed = true) {
        every { mitigation } returns state
        every { uuid } returns UUID.randomUUID()
        every { joinTime } returns 0L
        every { checkManager.getCheck(AiCheck::class.java) } returns aiCheck
      }

    return runtime(now).factsFor(player)
  }

  private fun runtime(now: Long): MitigationRuntime {
    val settings =
      MitigationSettings(
        enabled = true,
        logEnabled = false,
        score = MitigationsFile.DEFAULT_SCORE,
        skip = SkipSettings(bedrock = true, followAiRegions = true),
        rules = emptyList(),
      )
    return MitigationRuntime(
      plugin = mockk<Shard>(relaxed = true),
      playerDataManager = mockk<PlayerDataManager>(relaxed = true),
      configManager = mockk<ConfigManager>(relaxed = true),
      alertManager = mockk(relaxed = true),
      skip = mockk<MitigationSkip>(relaxed = true) { every { skipReason(any()) } returns null },
      engine = RuleEngine({ settings }, { now }, Random(1)),
      damageProcessor = mockk(relaxed = true),
      stamps = HitStamps(),
      debugManager = mockk(relaxed = true),
      scheduler = mockk(relaxed = true),
      logStore = mockk(relaxed = true),
      settings = { settings },
      clock = { now },
    )
  }
}
