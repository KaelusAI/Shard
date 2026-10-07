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
package ac.shard.detection

import ac.shard.ai.label.LabelledVerdict
import ac.shard.config.ConfigManager
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DetectionStateTest {

  @Test
  fun `two labels grow their own buffers independently`() {
    val check = createCheck()

    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 1.0)))

    assertEquals(5.0, check.labelBuffer("aim"), 1e-9)
    assertEquals(10.0, check.labelBuffer("trigger"), 1e-9)
    assertEquals(10.0, check.buffer, 1e-9, "overall is the highest label, not their sum")
  }

  @Test
  fun `only the label that crossed the threshold is reported and reset`() {
    val check = createCheck(flag = 8.0)

    val crossed = check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 1.0)))

    assertEquals(setOf("trigger"), crossed.keys)
    assertEquals(5.0, check.labelBuffer("aim"), 1e-9, "aim was below the flag and keeps its buffer")
    assertEquals(0.0, check.labelBuffer("trigger"), 1e-9)
  }

  @Test
  fun `a label the model stopped sending decays instead of hanging forever`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))
    assertEquals(5.0, check.labelBuffer("trigger"), 1e-9)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(4.0, check.labelBuffer("trigger"), 1e-9)
    assertEquals(10.0, check.labelBuffer("aim"), 1e-9)
  }

  @Test
  fun `a decayed label leaves the map so the overall buffer follows the live ones`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.92, "trigger" to 0.92)))

    repeat(3) { check.feedBuffers(attributed(mapOf("aim" to 0.92))) }

    assertTrue("trigger" !in check.labelBufferSnapshot(), "a drained label must not linger")
    assertEquals(check.labelBuffer("aim"), check.buffer, 1e-9)
  }

  @Test
  fun `a blocked label is not brought back by a late restore`() {
    val check = createCheck()
    val blocked = check.keyOf("x")
    check.blockRestore { it == blocked }

    check.restoreLabelBuffer("x", 20.0)
    check.restoreLabelBuffer("y", 30.0)

    assertEquals(0.0, check.labelBuffer("x"), 1e-9)
    assertEquals(30.0, check.labelBuffer("y"), 1e-9)
  }

  @Test
  fun `a fallback verdict freezes the other buffers instead of draining them`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    check.feedBuffers(LabelledVerdict(mapOf("_unattributed" to 0.5), false))

    assertEquals(5.0, check.labelBuffer("aim"), 1e-9)
    assertEquals(5.0, check.labelBuffer("trigger"), 1e-9)
  }

  @Test
  fun `a label scored near zero drains exactly as if it had been left out`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.05)))

    assertEquals(
      4.0,
      check.labelBuffer("trigger"),
      1e-9,
      "a multiclass window the model calls clean now names the class instead of omitting it, " +
        "and naming it has to drain the same amount omitting it did",
    )
  }

  @Test
  fun `a label scored between the two thresholds holds its buffer instead of moving it`() {
    val check = createCheck()
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.40)))

    assertEquals(
      5.0,
      check.labelBuffer("trigger"),
      1e-9,
      "the band between the thresholds is where the model says it does not know, and an " +
        "undecided window is not evidence of innocence any more than of guilt",
    )
  }

  @Test
  fun `a restored buffer from a retired label drains once the model reports again`() {
    val check = createCheck()
    check.restoreLabelBuffer("reach", 3.0)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(2.0, check.labelBuffer("reach"), 1e-9)
  }

  @Test
  fun `a label the model dropped from its set goes at once, not over dozens of windows`() {
    val check = createCheck(declared = listOf("aim"))
    check.restoreLabelBuffer("trigger", 49.0)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertTrue(
      "trigger" !in check.labelBufferSnapshot(),
      "the model can never speak to that label again, so decaying it just keeps the player listed",
    )
    assertEquals(5.0, check.buffer, 1e-9, "the overall buffer follows the labels that are left")
  }

  @Test
  fun `a scalar carried over from a single-headed model decays, it is not dropped`() {
    val check = createCheck(declared = listOf("aim"))
    check.restoreBuffer(4.0)

    check.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(
      3.0,
      check.labelBuffer(PlayerInference.UNATTRIBUTED_LABEL),
      1e-9,
      "the scalar was earned honestly, so it drains rather than vanishing",
    )
  }

  @Test
  fun `going back to a single-headed model keeps feeding the label it still declares`() {
    val check = createCheck(declared = listOf("aim", "trigger"))
    check.feedBuffers(attributed(mapOf("aim" to 0.95, "trigger" to 0.95)))

    val single = createCheckKeeping(check, declared = listOf("aim"))
    single.feedBuffers(attributed(mapOf("aim" to 0.95)))

    assertEquals(
      10.0,
      single.labelBuffer("aim"),
      1e-9,
      "aim is still declared, so its history carries across the model change instead of restarting",
    )
    assertTrue("trigger" !in single.labelBufferSnapshot())
  }

  @Test
  fun `with no label set negotiated yet, restored buffers drain instead of being thrown away`() {
    val check = createCheck(declared = emptyList())
    check.restoreLabelBuffer("aim", 30.0)
    check.restoreLabelBuffer("trigger", 20.0)

    check.feedBuffers(attributed(mapOf(PlayerInference.UNATTRIBUTED_LABEL to 0.95)))

    assertEquals(
      29.0,
      check.labelBuffer("aim"),
      1e-9,
      "an empty label set also means the server has not spoken yet, and a login restores " +
        "labels before it does, so dropping here would erase a returning cheater's history",
    )
    assertEquals(19.0, check.labelBuffer("trigger"), 1e-9)
    assertEquals(5.0, check.labelBuffer(PlayerInference.UNATTRIBUTED_LABEL), 1e-9)
  }

  private fun createCheckKeeping(source: DetectionState, declared: List<String>): DetectionState {
    val fresh = createCheck(declared = declared)
    for ((label, value) in source.labelBufferSnapshot()) fresh.restoreLabelBuffer(label, value)
    return fresh
  }

  private fun attributed(values: Map<String, Double>) = LabelledVerdict(values, attributed = true)

  private fun createCheck(
    flag: Double = 1_000.0,
    declared: List<String> = listOf("aim", "trigger", "reach"),
  ): DetectionState {
    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.suspiciousAlertsBuffer } returns 25.0
    every { configManager.settings } returns ac.shard.config.ShardSettings.DEFAULT
    every { configManager.streamProfile } returns profileWith(declared, flag)
    return DetectionState(configManager)
  }

  private fun profileWith(
    labels: List<String>,
    flag: Double = 50.0,
  ): ac.shard.ai.stream.StreamProfile {
    val primary =
      ac.shard.ai.stream.ModelSpec(
        "m",
        "M",
        null,
        ac.shard.ai.stream.Schedule.ATTACK,
        1,
        1,
        1,
        0,
        0,
        setOf(0),
        labels,
        if (labels.isEmpty()) ac.shard.ai.label.LabelMode.SINGLE
        else ac.shard.ai.label.LabelMode.MULTI_LABEL,
        emptySet(),
        emptyMap(),
        emptyMap(),
        ac.shard.ai.stream.BufferSpec(flag, 0.0, 100.0, 1.0, 32),
        1.0,
        ac.shard.ai.stream.roles(),
      )
    return ac.shard.ai.stream.StreamProfile(1, emptyList(), 1, 512, listOf(primary), primary, 0, "")
  }
}
