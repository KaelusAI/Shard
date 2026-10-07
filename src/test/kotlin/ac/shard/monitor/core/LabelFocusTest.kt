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
package ac.shard.monitor.core

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class LabelFocusTest {

  @Test
  fun `a bare label is canonicalised as before`() {
    assertEquals("x_y", LabelFocus.parse("X Y"))
  }

  @Test
  fun `an address keeps its model and canonicalises the label`() {
    assertEquals("c/x_y", LabelFocus.parse("C/X Y"))
  }

  @Test
  fun `an address with an impossible model or an empty label is rejected`() {
    assertNull(LabelFocus.parse("bad-model/x"))
    assertNull(LabelFocus.parse("c/!!!"))
  }
}
