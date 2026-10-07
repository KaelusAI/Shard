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
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.Schedule
import ac.shard.ai.stream.StreamProfile
import ac.shard.ai.stream.TEST_BUFFER
import ac.shard.ai.stream.roles
import ac.shard.config.MitigationsFile
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class MitigationPerModelTest {

  private val settings = MitigationsFile.OFF
  private val now = 1_775_000_000_000L

  @Suppress("LongParameterList")
  private fun model(
    id: String,
    schedule: Schedule,
    cadence: Int,
    span: Int,
    weight: Double,
    mitigate: Boolean = true,
  ) =
    ModelSpec(
      id,
      id.uppercase(),
      null,
      schedule,
      span / 2,
      span - span / 2,
      cadence,
      span,
      cadence,
      setOf(0),
      emptyList(),
      LabelMode.SINGLE,
      emptySet(),
      emptyMap(),
      emptyMap(),
      TEST_BUFFER,
      weight,
      roles(mitigate = mitigate),
    )

  private fun profile(primary: ModelSpec, vararg others: ModelSpec) =
    StreamProfile(1, emptyList(), 1, 512, listOf(primary, *others), primary, 0, "")

  private fun player(): Pair<ShardPlayer, MitigationState> {
    val state = MitigationState()
    val player = mockk<ShardPlayer>()
    every { player.mitigation } returns state
    return player to state
  }

  @Test
  fun `a lone mitigating primary scores exactly like a single model did`() {
    val a = model("a", Schedule.ATTACK, cadence = 6, span = 20, weight = 0.3)
    val b = model("b", Schedule.ATTACK, cadence = 9, span = 40, weight = 2.0, mitigate = false)
    val (player, state) = player()

    MitigationScorer({ settings }, { now }).record(player, profile(a, b), a, 0.97)

    val single = ScoreMath.contribution(0.97, 6, 20, settings.score)
    assertEquals(ScoreMath.clampToRange(single, settings.score), state.score, 1e-12)
    assertEquals(1L, state.answers)
  }

  @Test
  fun `each mitigating model adds its weighted share over the primary span`() {
    val a = model("a", Schedule.ATTACK, cadence = 10, span = 20, weight = 1.0)
    val c = model("c", Schedule.SLIDE, cadence = 4, span = 8, weight = 3.0)
    val (player, state) = player()

    MitigationScorer({ settings }, { now }).record(player, profile(a, c), c, 0.97)

    val expected = 0.75 * ScoreMath.contribution(0.97, 4, 20, settings.score)
    assertEquals(ScoreMath.clampToRange(expected, settings.score), state.score, 1e-12)
  }

  @Test
  fun `only the primary counts answers and moves the probability holds`() {
    val a = model("a", Schedule.ATTACK, cadence = 10, span = 20, weight = 1.0)
    val c = model("c", Schedule.SLIDE, cadence = 4, span = 8, weight = 1.0)
    val p = profile(a, c)
    val (player, state) = player()
    val scorer = MitigationScorer({ settings }, { now })

    scorer.record(player, p, c, 0.97)
    assertEquals(0L, state.answers)
    assertEquals(0L, state.lastAnswerAtMillis, "a second model must not wipe the primary holds")

    scorer.record(player, p, a, 0.97)
    assertEquals(1L, state.answers)
    assertEquals(now, state.lastAnswerAtMillis)
  }

  @Test
  fun `without a mitigating primary the first mitigating model counts answers and takes the whole share`() {
    val a = model("a", Schedule.ATTACK, cadence = 10, span = 20, weight = 1.0, mitigate = false)
    val c = model("c", Schedule.SLIDE, cadence = 4, span = 8, weight = 1.0)
    val (player, state) = player()

    MitigationScorer({ settings }, { now }).record(player, profile(a, c), c, 0.97)

    assertEquals(1L, state.answers)
    assertEquals(now, state.lastAnswerAtMillis)
    val whole = ScoreMath.contribution(0.97, 4, 20, settings.score)
    assertEquals(ScoreMath.clampToRange(whole, settings.score), state.score, 1e-12)
  }
}
