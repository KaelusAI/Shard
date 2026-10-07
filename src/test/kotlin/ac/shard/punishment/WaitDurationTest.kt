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
package ac.shard.punishment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaitDurationTest {
  @Test
  fun `every unit lands on the right number of ticks`() {
    assertEquals(20L, parseWaitTicks("1s"))
    assertEquals(1200L, parseWaitTicks("1m"))
    assertEquals(72_000L, parseWaitTicks("1h"))
    assertEquals(10L, parseWaitTicks("10t"))
    assertEquals(10L, parseWaitTicks("10"))
    assertEquals(2L, parseWaitTicks("100ms"))
    assertEquals(30L, parseWaitTicks("1.5s"))
  }

  @Test
  fun `a value that is not a duration is refused instead of read as zero`() {
    assertNull(parseWaitTicks("abc"))
    assertNull(parseWaitTicks("-5s"))
    assertNull(parseWaitTicks("NaNs"))
    assertEquals(0L, parseWaitTicks("0s"))
  }
}
