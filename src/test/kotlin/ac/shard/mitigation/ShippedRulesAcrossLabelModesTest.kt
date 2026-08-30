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

import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.VerdictResolver
import ac.shard.config.MitigationsFile
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ShippedRulesAcrossLabelModesTest {

  private val shipped: MitigationSettings by lazy {
    val stream =
      this::class.java.classLoader.getResourceAsStream("mitigations.yml")
        ?: error("bundled mitigations.yml is missing from the test classpath")
    MitigationsFile.read(
      YamlConfigurationLoader.builder().source { stream.bufferedReader() }.build().load(),
      mutableListOf(),
    )
  }

  @BeforeEach fun forgetPreviousComplaints() = VerdictResolver.forgetReports()

  @Test
  fun `a model with no labels at all scores where the shipped rules expect`() {
    assertShippedLine(
      caught = read(labels = emptyList(), probabilities = null, overall = 0.96),
      cleared = read(labels = emptyList(), probabilities = null, overall = 0.03),
    )
  }

  @Test
  fun `one declared label scores exactly where no labels did`() {
    assertShippedLine(
      caught = read(labels = listOf("aim"), probabilities = listOf(0.96), overall = 0.96),
      cleared = read(labels = listOf("aim"), probabilities = listOf(0.03), overall = 0.03),
    )
  }

  @Test
  fun `a multilabel model scores on its loudest mechanic`() {
    val labels = listOf("aim", "trigger")
    assertShippedLine(
      caught = read(labels, LabelMode.MULTI_LABEL, listOf(0.96, 0.05), 0.96),
      cleared = read(labels, LabelMode.MULTI_LABEL, listOf(0.03, 0.02), 0.03),
    )
  }

  @Test
  fun `a multiclass model that told the server everything scores on the same line`() {
    val labels = listOf("legit", "aim", "trigger")
    assertShippedLine(
      caught = read(labels, LabelMode.MULTI_CLASS, listOf(0.02, 0.95, 0.03), 0.95),
      cleared = read(labels, LabelMode.MULTI_CLASS, listOf(0.96, 0.02, 0.02), 0.02),
    )
  }

  @Test
  fun `a multiclass model that left out its thresholds falls back and still scores`() {
    val labels = listOf("legit", "aim", "trigger")
    assertShippedLine(
      caught =
        read(labels, LabelMode.MULTI_CLASS, listOf(0.02, 0.95, 0.03), 0.95, thresholds = false),
      cleared =
        read(labels, LabelMode.MULTI_CLASS, listOf(0.96, 0.02, 0.02), 0.02, thresholds = false),
    )
  }

  private fun assertShippedLine(caught: Double, cleared: Double) {
    val tax = shipped.rule("tax")!!
    val blatant = shipped.rule("blatant")!!

    assertTrue(
      tax.matches(facts(caught)),
      "the shipped 0.90 door is the same door in every mode, and this shape read $caught",
    )
    assertTrue(
      blatant.matches(facts(caught, buffer = 40.0, holds = 9_000L)),
      "the strictest rule reads the same number the buffer was fed, so it opens here too",
    )
    assertTrue(
      !tax.matches(facts(cleared)),
      "an honest player reads $cleared in this shape, which must stay under the softest rule",
    )
  }

  private fun read(
    labels: List<String>,
    mode: LabelMode? = null,
    probabilities: List<Double>? = null,
    overall: Double = 0.0,
    thresholds: Boolean = true,
  ): Double {
    val legit = setOf("legit")
    val resolver =
      VerdictResolver(
        settings = {
          VerdictResolver.Settings(
            labels = labels,
            mode = mode,
            split = true,
            maxTracked = 32,
            legitClasses = legit,
            thresholdedLabels = if (thresholds) labels.toSet() - legit else emptySet(),
          )
        },
        warn = {},
      )
    return resolver.resolve(probabilities, overall).values.values.maxOrNull() ?: 0.0
  }

  private fun facts(probability: Double, buffer: Double = 0.0, holds: Long = 0L) =
    RuleFacts(
      score = 0.0,
      buffer = buffer,
      probability = probability,
      answers = 400L,
      sessions = 1,
      days = 1,
      onlineMillis = 600_000L,
      inCombat = true,
      probabilityHolds = mapOf(HoldKey(0.90, 6_000L) to holds),
    )
}
