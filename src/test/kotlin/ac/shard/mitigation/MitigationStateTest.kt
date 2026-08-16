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

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MitigationStateTest {

  private val six = HoldKey(0.90, 6_000L)
  private val thirty = HoldKey(0.90, 30_000L)
  private val books = HoldAccounting(setOf(six, thirty), coverMillis = 2_000L, forgetRate = 0.5)

  private val start = 1_775_000_000_000L

  private fun MitigationState.answer(probability: Double, at: Long) =
    noteProbability(probability, at, books)

  @Test
  fun `an answer only credits the movement it was actually computed from`() {
    val state = MitigationState()

    state.answer(0.95, start)

    assertEquals(
      2_000L,
      state.probabilityHolds()[six],
      "the first answer covers its own window and not a millisecond more",
    )
  }

  @Test
  fun `time nobody looked at is never credited`() {
    val state = MitigationState()

    state.answer(0.95, start)
    state.answer(0.95, start + 500L)
    state.answer(0.95, start + 1_000L)

    assertEquals(
      3_000L,
      state.probabilityHolds()[six],
      "two more half-second steps on top of the first window",
    )
  }

  @Test
  fun `a hold stops growing the moment the answers stop`() {
    val state = MitigationState()
    repeat(9) { state.answer(0.99, start + it * 500L) }
    val reached = state.probabilityHolds()[six]

    assertEquals(6_000L, reached, "six seconds of watched movement, and it caps there")
    assertEquals(
      reached,
      state.probabilityHolds()[six],
      "a fight that ended must not keep earning suspicion while the player stands still",
    )
  }

  @Test
  fun `a single answer below the threshold wipes the hold`() {
    val state = MitigationState()
    repeat(6) { state.answer(0.95, start + it * 500L) }

    state.answer(0.40, start + 3_000L)

    assertEquals(null, state.probabilityHolds()[six], "one clean window is enough to break it")
  }

  @Test
  fun `a short pause costs nothing, a long one eats what was earned`() {
    val state = MitigationState()
    repeat(5) { state.answer(0.95, start + it * 500L) }
    val before = state.probabilityHolds().getValue(six)

    state.answer(0.95, start + 2_000L + 1_500L)
    assertEquals(
      before + 1_500L,
      state.probabilityHolds()[six],
      "a gap the model still covers is watched movement, not a pause",
    )

    val held = state.probabilityHolds().getValue(six)
    state.answer(0.95, start + 3_500L + 12_000L)
    assertEquals(
      held - 5_000L + 2_000L,
      state.probabilityHolds()[six],
      "ten unseen seconds erode five, then the fresh window credits two",
    )
  }

  @Test
  fun `fighting in bursts does not reset the count, but a long break does`() {
    val bursty = MitigationState()
    repeat(10) { bursty.answer(0.95, start + it * 500L) }
    bursty.answer(0.95, start + 5_000L + 4_000L)

    assertTrue(
      bursty.probabilityHolds().getValue(six) >= 6_000L,
      "five seconds of fighting, four of walking, five more - the count carries over",
    )

    val patient = MitigationState()
    repeat(6) { patient.answer(0.95, start + it * 500L) }
    patient.answer(0.95, start + 2_500L + 20_000L)

    assertTrue(
      patient.probabilityHolds().getValue(six) < 6_000L,
      "a twenty second break is a different fight",
    )
  }

  @Test
  fun `two rules on the same threshold keep their own counts`() {
    val state = MitigationState()
    repeat(20) { state.answer(0.99, start + it * 500L) }

    assertEquals(6_000L, state.probabilityHolds()[six], "the six second rule caps at six")
    assertEquals(11_500L, state.probabilityHolds()[thirty], "the thirty second one keeps counting")
  }

  @Test
  fun `the state remembers when it last heard from the model`() {
    val state = MitigationState()

    assertEquals(0L, state.lastAnswerAtMillis, "nothing heard yet")
    state.answer(0.5, start)
    assertEquals(start, state.lastAnswerAtMillis)
  }
}
