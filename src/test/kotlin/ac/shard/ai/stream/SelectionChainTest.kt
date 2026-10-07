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
package ac.shard.ai.stream

import ac.shard.data.AttackWindowTracker
import ac.shard.data.TickBuffer
import ac.shard.data.TickData
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SelectionChainTest {

  private fun attack(id: String, pre: Int, post: Int, step: Int) =
    model(id, Schedule.ATTACK, pre = pre, post = post, step = step)

  private fun slide(id: String, window: Int, stride: Int) =
    model(id, Schedule.SLIDE, window = window, stride = stride)

  @Suppress("LongParameterList")
  private fun model(
    id: String,
    schedule: Schedule,
    pre: Int = 0,
    post: Int = 0,
    step: Int = 0,
    window: Int = 0,
    stride: Int = 0,
  ) =
    ModelSpec(
      id,
      id,
      null,
      schedule,
      pre,
      post,
      step,
      window,
      stride,
      setOf(MELEE),
      emptyList(),
      null,
      emptySet(),
      emptyMap(),
      emptyMap(),
      TEST_BUFFER,
      1.0,
      roles(),
    )

  private fun todaysEnds(
    attacks: Set<Long>,
    rows: Long,
    pre: Int,
    post: Int,
    step: Int,
  ): List<Long> {
    val buf = TickBuffer(pre + post + 1)
    val tracker = AttackWindowTracker()
    var sinceLast = Int.MAX_VALUE / 2
    val ends = mutableListOf<Long>()
    for (seq in 1..rows) {
      val anchor = seq in attacks
      buf.captureRaw { it.sequenceId = 1 }
      if (anchor) buf.markAttack()
      if (sinceLast < Int.MAX_VALUE) sinceLast++
      tracker.onTick(buf, anchor, TickData.START_MELEE_PLAYER, post) { _, _, kind ->
        if (kind == TickData.START_MELEE_PLAYER && sinceLast >= step) {
          ends += buf.currentSeq()
          sinceLast = 0
        }
      }
      buf.advance()
    }
    return ends
  }

  private fun chainEnds(attacks: Set<Long>, m: ModelSpec): List<Long> {
    val chain = SelectionChain(listOf(m))
    return attacks.sorted().flatMap { chain.onAnchor(it, MELEE) }.map { it.end }
  }

  @Test
  fun `attack schedule reproduces todays tracker and step gate`() {
    val shapes = listOf(Triple(20, 20, 20), Triple(60, 30, 30), Triple(6, 6, 6), Triple(20, 20, 30))
    repeat(40) { seed ->
      val rng = Random(seed)
      val rows = 3000L
      val density = listOf(0.02, 0.1, 0.4, 0.9)[seed % 4]
      val attacks = (1..rows - 200).filter { rng.nextDouble() < density }.toSet()
      for ((pre, post, step) in shapes) {
        val expected = todaysEnds(attacks, rows, pre, post, step)
        val actual = chainEnds(attacks, attack("m", pre, post, step)).filter { it <= rows }
        assertEquals(expected, actual, "seed $seed shape $pre/$post/$step")
      }
    }
  }

  @Test
  fun `step gate takes the anchor but skips the window`() {
    val chain = SelectionChain(listOf(attack("m", 20, 20, 30)))
    assertEquals(listOf(28L), chain.onAnchor(9, MELEE).map { it.end })
    assertTrue(chain.onAnchor(29, MELEE).isEmpty())
    assertEquals(29L, chain.seed("m").lastTaken)
    assertTrue(chain.onAnchor(40, MELEE).isEmpty())
    assertEquals(listOf(68L), chain.onAnchor(49, MELEE).map { it.end })
  }

  @Test
  fun `slide schedule ends on the stride grid and covers the anchor`() {
    val chain = SelectionChain(listOf(slide("f", 10, 5)))
    assertEquals(listOf(10L, 15L), chain.onAnchor(10, MELEE).map { it.end })
    assertEquals(listOf(20L, 25L), chain.onAnchor(20, MELEE).map { it.end })
    assertEquals(listOf(30L), chain.onAnchor(21, MELEE).map { it.end })
    assertTrue(chain.onAnchor(22, MELEE).isEmpty())
    assertEquals(listOf(45L, 50L), chain.onAnchor(41, MELEE).map { it.end })
  }

  @Test
  fun `anchor kinds outside a model are ignored`() {
    val chain = SelectionChain(listOf(attack("m", 20, 20, 20)))
    assertTrue(chain.onAnchor(10, 3).isEmpty())
    assertEquals(listOf(29L), chain.onAnchor(10, MELEE).map { it.end })
  }

  @Test
  fun `history lookups and pruning`() {
    val chain = SelectionChain(listOf(attack("b", 20, 20, 20), slide("f", 10, 5)))
    chain.onAnchor(10, MELEE)
    chain.onAnchor(100, MELEE)
    assertEquals(100L, chain.latestAnchor())
    assertEquals(listOf(10L, 15L, 29L), chain.attemptsEndingIn(1, 30).map { it.end })
    assertTrue(chain.hasAttemptEndingAtLeast(100, 119))
    chain.pruneBelow(50)
    assertTrue(chain.attemptsEndingIn(1, 50).isEmpty())
    assertEquals(listOf(100L, 105L, 119L), chain.attemptsEndingIn(1, 200).map { it.end })
  }

  @Test
  fun `adopting keeps seeds of unchanged models only`() {
    val chain = SelectionChain(listOf(attack("b", 20, 20, 20), attack("p", 60, 30, 30)))
    chain.onAnchor(10, MELEE)
    val profile = profileOf(listOf(attack("b", 20, 20, 20), attack("p", 60, 30, 20)))
    val next = chain.adopting(profile)
    assertEquals(chain.seed("b"), next.seed("b"))
    assertEquals(Seed(), next.seed("p"))
  }

  @Test
  fun `progress reports the pending attack window`() {
    val chain = SelectionChain(listOf(attack("b", 20, 20, 20)))
    chain.onAnchor(10, MELEE)
    assertEquals(listOf(1, 20), chain.progress("b", 10)!!.toList())
    assertEquals(listOf(20, 20), chain.progress("b", 29)!!.toList())
    assertEquals(null, chain.progress("b", 30))
  }

  private fun profileOf(models: List<ModelSpec>) =
    StreamProfile(1, emptyList(), 5, 512, models, models.first(), 60_000, "")

  private companion object {
    val MELEE: Short = TickData.START_MELEE_PLAYER
  }

  @Test
  fun `continuous windows follow the stride grid once each`() {
    val m = slide("p", 16, 64).copy(schedule = Schedule.CONTINUOUS, anchorKinds = emptySet())
    val chain = SelectionChain(listOf(m))
    val ends = (1L..300L).flatMap { t -> chain.onTick(t) + chain.onTick(t) }.map { it.end }
    assertEquals(listOf(64L, 128L, 192L, 256L), ends)
    assertTrue(chain.onAnchor(300, MELEE).isEmpty(), "events never start continuous windows")
  }
}
