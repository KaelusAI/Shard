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

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class HitStampsTest {

  private class Ticker(var now: Long = 1_775_000_000_000L)

  @Test
  fun `one blast reaches everyone it caught, not just the first of them`() {
    val ticker = Ticker()
    val stamps = HitStamps { ticker.now }
    val crystal = UUID.randomUUID()
    val owner = UUID.randomUUID()
    stamps.remember(crystal, owner, 0.2)

    assertEquals(0.2, stamps.peek(crystal)?.multiplier)
    assertEquals(0.2, stamps.peek(crystal)?.multiplier, "a second victim of the same blast")
    assertEquals(0.2, stamps.peek(crystal)?.multiplier, "and a third")
  }

  @Test
  fun `an arrow is spent once and gone`() {
    val stamps = HitStamps()
    val arrow = UUID.randomUUID()
    stamps.remember(arrow, UUID.randomUUID(), 0.5)

    assertEquals(0.5, stamps.take(arrow)?.multiplier)
    assertNull(stamps.take(arrow), "the same arrow cannot land twice")
  }

  @Test
  fun `a stamp nobody collected stops counting after its time`() {
    val ticker = Ticker()
    val stamps = HitStamps { ticker.now }
    val crystal = UUID.randomUUID()
    stamps.remember(crystal, UUID.randomUUID(), 0.3)

    ticker.now += 31_000L

    assertNull(stamps.peek(crystal))
    assertNull(stamps.take(crystal))
  }

  @Test
  fun `a full multiplier is not worth remembering`() {
    val stamps = HitStamps()
    val crystal = UUID.randomUUID()

    stamps.remember(crystal, UUID.randomUUID(), 1.0)

    assertNull(stamps.peek(crystal), "nothing to scale means nothing to carry")
  }
}
