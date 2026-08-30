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

import ac.shard.checks.impl.ai.ViolationBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MismatchCostTest {

  @BeforeEach fun forgetPreviousComplaints() = VerdictResolver.forgetReports()

  @Test
  fun `a reordered multilabel answer cannot hurt an honest player`() {
    val resolver = resolver(listOf("aim", "trigger"), LabelMode.MULTI_LABEL, emptySet())

    val straight = resolver.resolve(listOf(0.03, 0.02), 0.03)
    val swapped = resolver.resolve(listOf(0.02, 0.03), 0.03)

    assertEquals(
      0,
      windowsToFlag(straight.values.values.max()),
      "an honest player reads low on every head, so permuting the heads permutes small numbers",
    )
    assertEquals(0, windowsToFlag(swapped.values.values.max()))
  }

  @Test
  fun `a reordered multiclass answer moves the clean mass onto a cheat class`() {
    val labels = listOf("aim", "trigger", "legit")
    val resolver = resolver(labels, LabelMode.MULTI_CLASS, setOf("legit"))

    val drifted = resolver.resolve(listOf(0.96, 0.02, 0.02), 0.96)

    val score = drifted.values.getValue("aim")
    assertTrue(score > 0.9, "0.96 of clean confidence now reads as 0.96 of aim: $score")
    assertEquals(
      9,
      windowsToFlag(score),
      "nine scored windows, and at one anchor per 32 ticks that is about fourteen seconds of a fight",
    )
  }

  @Test
  fun `multiclass with no clean class named drops the window instead of scoring the clean mass`() {
    val labels = listOf("legit", "aim", "trigger")
    val resolver = resolver(labels, LabelMode.MULTI_CLASS, emptySet())

    val verdict = resolver.resolve(listOf(0.96, 0.02, 0.02), 0.96)

    assertEquals(
      emptyMap(),
      verdict.values,
      "the merged fallback takes the loudest class that is not clean, and with no clean class " +
        "named that is the clean class itself, which would flag an honest player in nine windows",
    )
    assertTrue(!verdict.attributed, "a dropped window must freeze the buffers, not drain them")
  }

  @Test
  fun `multiclass that named its clean class still merges when only the thresholds are missing`() {
    val labels = listOf("legit", "aim", "trigger")
    val resolver = resolver(labels, LabelMode.MULTI_CLASS, setOf("legit"), thresholded = emptySet())

    val verdict = resolver.resolve(listOf(0.96, 0.02, 0.02), 0.96)

    assertEquals(
      mapOf(LabelKey.UNATTRIBUTED to 0.02),
      verdict.values,
      "here the clean class is known and dropped, so merging is still a safe reading",
    )
  }

  private fun windowsToFlag(probability: Double): Int {
    val settings = ViolationBuffer.Settings(0.90, 0.10, 100.0, 1.0)
    val buffer = ViolationBuffer()
    repeat(MAX_WINDOWS) { i ->
      buffer.feed(probability, settings)
      if (buffer.value >= FLAG) return i + 1
    }
    return 0
  }

  private fun resolver(
    labels: List<String>,
    mode: LabelMode,
    legitClasses: Set<String>,
    thresholded: Set<String>? = null,
  ) =
    VerdictResolver(
      settings = {
        VerdictResolver.Settings(
          labels = labels,
          mode = mode,
          split = true,
          maxTracked = 32,
          legitClasses = legitClasses,
          thresholdedLabels = thresholded ?: (labels.toSet() - legitClasses),
        )
      },
      warn = {},
    )

  private companion object {
    const val FLAG = 50.0
    const val MAX_WINDOWS = 500
  }
}
