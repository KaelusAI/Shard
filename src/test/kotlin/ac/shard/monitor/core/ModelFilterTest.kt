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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelFilterTest {
  @Test
  fun `a list of models is read in any order and case`() {
    assertEquals("a,b", ModelFilter.parse(" B, a ,b"))
    assertEquals(ModelFilter.ALL, ModelFilter.parse("all"))
    assertEquals(ModelFilter.PRIMARY, ModelFilter.parse("primary"))
    assertNull(ModelFilter.parse("a b"))
    assertNull(ModelFilter.parse(","))
    assertNull(ModelFilter.parse(List(40) { "model$it" }.joinToString(",")))
  }

  @Test
  fun `a list shows exactly the models it names, the main one included`() {
    assertTrue(ModelFilter.shows(ModelFilter.PRIMARY, "a", primary = true))
    assertFalse(ModelFilter.shows(ModelFilter.PRIMARY, "b", primary = false))
    assertTrue(ModelFilter.shows(ModelFilter.ALL, "b", primary = false))
    assertTrue(ModelFilter.shows("b,c", "c", primary = false))
    assertFalse(ModelFilter.shows("b,c", "d", primary = false))
    assertFalse(ModelFilter.shows("b", "a", primary = true), "the main model is not forced in")
    assertTrue(ModelFilter.shows("a,b", "a", primary = true))
  }
}
