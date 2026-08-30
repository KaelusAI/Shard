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
package ac.shard.ai.label

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VerdictResolverTest {

  private val warnings = mutableListOf<String>()

  @BeforeEach fun forgetPreviousComplaints() = VerdictResolver.forgetReports()

  @Suppress("LongParameterList")
  @Test
  fun `names win over positions, so a reordered array cannot mislabel a score`() {
    val verdict =
      resolver(labels = listOf("aim", "trigger"))
        .resolve(listOf(0.96, 0.35), 0.96, named = mapOf("trigger" to 0.96, "aim" to 0.35))

    assertEquals(
      mapOf("aim" to 0.35, "trigger" to 0.96),
      verdict.values,
      "the array says aim is 0.96, the names say it is 0.35, and the names are the truth",
    )
  }

  @Test
  fun `a named map missing a declared label falls back instead of guessing`() {
    val verdict =
      resolver(labels = listOf("aim", "trigger")).resolve(null, 0.9, named = mapOf("aim" to 0.35))

    assertEquals(emptyMap(), verdict.values, "a partial map is a broken answer, not a verdict")
    assertTrue(warnings.any { it.contains("left out") })
  }

  @Test
  fun `declared labels with no probabilities at all is a broken answer, not a verdict`() {
    val verdict = resolver(labels = listOf("aim", "trigger")).resolve(null, 0.9)

    assertEquals(emptyMap(), verdict.values, "an unknown number must not reach a ban ladder")
    assertFalse(
      verdict.attributed,
      "attributed would drain every label buffer, so a server that stops sending labels would " +
        "quietly erase the evidence it already gave us",
    )
    assertTrue(warnings.any { it.contains("no probabilities") })
  }

  @Test
  fun `a model that never declared labels is not broken, it is just old`() {
    val verdict = resolver(labels = emptyList()).resolve(null, 0.9)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.9), verdict.values)
    assertTrue(verdict.attributed, "nothing was promised, so nothing is missing")
    assertTrue(warnings.isEmpty())
  }

  @Test
  fun `split never still merges a real array into one real verdict`() {
    val verdict =
      resolver(labels = listOf("aim", "trigger"), split = false).resolve(listOf(0.3, 0.8), 0.9)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.8), verdict.values)
    assertTrue(verdict.attributed, "the operator asked for one buffer; that is not a failure")
  }

  @Test
  fun `a multi-label model never has its window scored on the scalar`() {
    val verdict =
      resolver(labels = listOf("aim", "trigger"), mode = LabelMode.MULTI_LABEL, split = false)
        .resolve(null, 0.97)

    assertEquals(
      emptyMap(),
      verdict.values,
      "split never merges labels, it does not licence reading a number the model never promised",
    )
    assertTrue(warnings.any { it.contains("multilabel") })
  }

  @Test
  fun `a model that never declared labels may still be scored on its one number`() {
    val verdict = resolver(labels = emptyList()).resolve(null, 0.97)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.97), verdict.values)
    assertTrue(verdict.attributed, "nothing was promised, so the one number is the whole answer")
  }

  @Test
  fun `one declared label and one number is the whole contract, not a broken answer`() {
    val verdict = resolver(labels = listOf("aim")).resolve(null, 0.93)

    assertEquals(
      mapOf("aim" to 0.93),
      verdict.values,
      "a single-headed model has one number and no array to send; the scalar is that label",
    )
    assertTrue(verdict.attributed)
    assertTrue(warnings.isEmpty(), "this is the normal shape, not something to complain about")
  }

  @Test
  fun `an uninformative softmax scores one half whatever the class count is`() {
    val four = resolver(labels = listOf("legit", "a", "b", "c"), mode = LabelMode.MULTI_CLASS)
    val verdict = four.resolve(listOf(0.25, 0.25, 0.25, 0.25), 0.75)

    assertEquals(
      0.5,
      verdict.values.values.first(),
      1e-9,
      "raw cheat mass would be 0.75 here and 0.95 with twenty clients, so a model that learned " +
        "nothing would clear a 0.9 threshold on arithmetic alone",
    )
  }

  @Test
  fun `a long client list leaning clean scores below the dead zone, not above it`() {
    val labels = listOf("legit") + (1..19).map { "c$it" }
    val probs = listOf(0.10) + List(19) { 0.90 / 19 }
    val verdict = resolver(labels = labels, mode = LabelMode.MULTI_CLASS).resolve(probs, 0.9)

    assertTrue(
      verdict.values.values.first() < 0.4,
      "raw mass is 0.90 and would grow a buffer, though the model leans twice as clean as chance",
    )
  }

  @Test
  fun `two clients neck and neck feed the shared buffer, not a guessed name`() {
    val verdict =
      resolver(labels = listOf("legit", "nursultan", "celestial"), mode = LabelMode.MULTI_CLASS)
        .resolve(listOf(0.20, 0.40, 0.40), 0.8)

    assertEquals(
      setOf(LabelKey.UNATTRIBUTED),
      verdict.values.keys,
      "picking one by a coin flip both misnames it to the operator and double counts the " +
        "evidence across overlapping windows",
    )
    assertTrue(verdict.values.values.first() > 0.5, "the detection itself is not in doubt")
  }

  @Test
  fun `a client that clearly won is named`() {
    val verdict =
      resolver(labels = listOf("legit", "nursultan", "celestial"), mode = LabelMode.MULTI_CLASS)
        .resolve(listOf(0.05, 0.80, 0.15), 0.95)

    assertEquals(setOf("nursultan"), verdict.values.keys)
  }

  @Suppress("LongParameterList")
  private fun resolver(
    labels: List<String> = emptyList(),
    mode: LabelMode? = null,
    split: Boolean = true,
    maxTracked: Int = 32,
    legitClasses: Set<String> = setOf("legit"),
    thresholded: Set<String>? = null,
  ) =
    VerdictResolver(
      settings = {
        VerdictResolver.Settings(
          labels,
          mode,
          split,
          maxTracked,
          legitClasses,
          thresholded ?: labels.toSet(),
        )
      },
      warn = { warnings += it },
    )

  @Test
  fun `a single-headed model keeps working exactly as before`() {
    val verdict = resolver().resolve(null, 0.93)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.93), verdict.values)
    assertTrue(verdict.attributed, "a model without labels is answering honestly, not failing")
  }

  @Test
  fun `a multi-label model feeds every head`() {
    val verdict = resolver(labels = listOf("aim", "trigger")).resolve(listOf(0.97, 0.12), 0.97)

    assertEquals(mapOf("aim" to 0.97, "trigger" to 0.12), verdict.values)
    assertTrue(verdict.attributed)
  }

  @Test
  fun `a length mismatch drops the window instead of scoring it on the scalar`() {
    val verdict = resolver(labels = listOf("aim", "trigger")).resolve(listOf(0.9), 0.9)

    assertEquals(emptyMap(), verdict.values, "the scalar means nothing once the model misbehaves")
    assertFalse(verdict.attributed, "a broken response must not look like a clean verdict")
  }

  @Test
  fun `multiclass without per-label thresholds refuses to split instead of going quiet`() {
    val resolver =
      resolver(
        labels = listOf("legit", "aim", "trigger"),
        mode = LabelMode.MULTI_CLASS,
        thresholded = emptySet(),
      )

    val verdict = resolver.resolve(listOf(0.05, 0.55, 0.40), 0.95)

    assertEquals(
      mapOf(LabelKey.UNATTRIBUTED to 0.55),
      verdict.values,
      "the fallback reads the array it already has: 0.05 is the clean class and is dropped, " +
        "and it never trusts the scalar, which for a softmax model may be confidence in clean",
    )
    assertTrue(warnings.any { it.contains("multiclass") })
  }

  @Test
  fun `multiclass refuses to split when only some labels carry a threshold`() {
    val resolver =
      resolver(
        labels = listOf("legit", "aim", "trigger"),
        mode = LabelMode.MULTI_CLASS,
        thresholded = setOf("aim"),
      )

    val verdict = resolver.resolve(listOf(0.05, 0.40, 0.55), 0.95)

    assertEquals(
      mapOf(LabelKey.UNATTRIBUTED to 0.55),
      verdict.values,
      "trigger would fall back on 0.9/0.1 and its buffer would never move off zero",
    )
    assertTrue(warnings.any { it.contains("multiclass") })
  }

  @Test
  fun `a legit class needs no threshold of its own to count as covered`() {
    val resolver =
      resolver(
        labels = listOf("legit", "aim"),
        mode = LabelMode.MULTI_CLASS,
        legitClasses = setOf("legit"),
        thresholded = setOf("aim"),
      )

    val verdict = resolver.resolve(listOf(0.10, 0.90), 0.90)

    assertEquals(mapOf("aim" to 0.90), verdict.values, "nothing ever feeds a clean class")
    assertTrue(warnings.none { it.contains("multiclass") })
  }

  @Test
  fun `a multi-class model feeds only the winner`() {
    val resolver =
      resolver(labels = listOf("legit", "aim", "trigger"), mode = LabelMode.MULTI_CLASS)

    val verdict = resolver.resolve(listOf(0.05, 0.60, 0.35), 0.95)

    assertEquals(setOf("aim"), verdict.values.keys, "classes exclude each other")
    assertEquals(
      0.905,
      verdict.values.getValue("aim"),
      1e-3,
      "the winner's own 0.60 answers which mechanic, not how sure the model is that there is one",
    )
  }

  @Test
  fun `two clean classes do not suppress the score the way one does`() {
    val verdict =
      resolver(
          labels = listOf("vanilla", "modded", "nursultan", "celestial"),
          mode = LabelMode.MULTI_CLASS,
          legitClasses = setOf("vanilla", "modded"),
        )
        .resolve(listOf(0.25, 0.25, 0.25, 0.25), 0.5)

    assertEquals(
      0.5,
      verdict.values.values.first(),
      1e-9,
      "an uninformative answer is one half however the classes are split; counting every class " +
        "but one as a cheat would score this 0.25 and quietly hide half the client list",
    )
  }

  @Test
  fun `multiclass with no clean class among the labels refuses to split`() {
    val verdict =
      resolver(
          labels = listOf("aim", "trigger"),
          mode = LabelMode.MULTI_CLASS,
          legitClasses = setOf("clean"),
        )
        .resolve(listOf(0.45, 0.55), 0.55)

    assertEquals(
      emptyMap(),
      verdict.values,
      "softmax over cheat classes alone always sums to one, so merging it would score every " +
        "window as a cheat with nowhere for the mass to go",
    )
    assertFalse(verdict.attributed, "a window nobody can read must freeze the buffers, not drain")
    assertTrue(warnings.any { it.contains("multiclass") })
  }

  @Test
  fun `a window the model cannot call lands in the dead zone instead of draining`() {
    val verdict =
      resolver(labels = listOf("legit", "aim"), mode = LabelMode.MULTI_CLASS)
        .resolve(listOf(0.60, 0.40), 0.40)

    assertEquals(
      0.40,
      verdict.values.getValue("aim"),
      1e-9,
      "six to four is the model saying it does not know, and the buffer already has a band for " +
        "that; draining on it would treat 60/40 the same as 99/1",
    )
  }

  @Test
  fun `a multi-class model that calls the player clean drains rather than feeds`() {
    val resolver = resolver(labels = listOf("legit", "aim"), mode = LabelMode.MULTI_CLASS)

    val verdict = resolver.resolve(listOf(0.95, 0.05), 0.05)

    assertFalse("legit" in verdict.values, "the clean class must never build a buffer of its own")
    assertTrue(
      verdict.values.getValue("aim") < 0.1,
      "a score this far under the legit threshold drains the buffer instead of holding it",
    )
    assertTrue(verdict.attributed, "a clean window has to drain what a dirty one built")
  }

  @Test
  fun `turning splitting off merges the heads into one buffer`() {
    val verdict =
      resolver(labels = listOf("aim", "trigger"), split = false).resolve(listOf(0.3, 0.98), 0.98)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.98), verdict.values)
    assertTrue(verdict.attributed)
  }

  @Test
  fun `a clean class never reaches a buffer, even with splitting turned off`() {
    val verdict =
      resolver(labels = listOf("legit", "aim"), split = false).resolve(listOf(0.97, 0.02), 0.02)

    assertEquals(
      mapOf(LabelKey.UNATTRIBUTED to 0.02),
      verdict.values,
      "merging the heads must not hand the clean class's own probability to the buffer",
    )
  }

  @Test
  fun `a clean class never reaches a buffer under multilabel either`() {
    val verdict =
      resolver(labels = listOf("legit", "aim"), mode = LabelMode.MULTI_LABEL)
        .resolve(listOf(0.97, 0.02), 0.02)

    assertEquals(setOf("aim"), verdict.values.keys)
  }

  @Test
  fun `multiclass without thresholds refuses to split even when splitting is off`() {
    val verdict =
      resolver(
          labels = listOf("legit", "aim"),
          mode = LabelMode.MULTI_CLASS,
          split = false,
          thresholded = emptySet(),
        )
        .resolve(listOf(0.97, 0.02), 0.95)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.02), verdict.values)
    assertTrue(warnings.any { it.contains("multiclass") })
  }

  @Test
  fun `a declared single-label model keeps the name the model gave it`() {
    val verdict =
      resolver(labels = listOf("aim"), mode = LabelMode.SINGLE).resolve(listOf(0.97), 0.97)

    assertEquals(mapOf("aim" to 0.97), verdict.values, "one label is already one number")
  }

  @Test
  fun `a declared single model with several labels collapses to one buffer`() {
    val verdict =
      resolver(labels = listOf("aim", "trigger"), mode = LabelMode.SINGLE)
        .resolve(listOf(0.3, 0.97), 0.97)

    assertEquals(mapOf(LabelKey.UNATTRIBUTED to 0.97), verdict.values)
  }

  @Test
  fun `a probability above one is clamped instead of flagging on the spot`() {
    val verdict = resolver(labels = listOf("aim")).resolve(listOf(5.0), 5.0)

    assertEquals(mapOf("aim" to 1.0), verdict.values)
    assertTrue(warnings.any { it.contains("outside 0..1") })
  }

  @Test
  fun `a negative probability cannot be used to wash a buffer clean`() {
    val verdict = resolver(labels = listOf("aim")).resolve(listOf(-3.0), -3.0)

    assertEquals(mapOf("aim" to 0.0), verdict.values)
  }

  @Test
  fun `a probability that is not a number becomes zero rather than a silent no-op`() {
    val verdict = resolver(labels = listOf("aim")).resolve(listOf(Double.NaN), Double.NaN)

    assertEquals(mapOf("aim" to 0.0), verdict.values)
  }

  @Test
  fun `more labels than the server tracks are cut off`() {
    val labels = (1..50).map { "label_$it" }
    val verdict = resolver(labels = labels, maxTracked = 4).resolve(labels.map { 0.5 }, 0.5)

    assertEquals(4, verdict.values.size)
  }

  @Test
  fun `a broken server is reported once, not on every response`() {
    val resolver = resolver(labels = listOf("aim", "trigger"))

    repeat(100) { resolver.resolve(listOf(0.9), 0.9) }

    assertTrue(warnings.size <= 1, "one signature must not produce ${warnings.size} log lines")
  }
}
