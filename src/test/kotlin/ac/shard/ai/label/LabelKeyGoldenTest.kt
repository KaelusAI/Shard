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
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class LabelKeyGoldenTest {

  @Test
  fun `short names keep their spelling folded into one key`() {
    assertEquals("aim_assist", LabelKey.canonical("Aim Assist"))
    assertEquals("aim_assist", LabelKey.canonical("AIM-ASSIST"))
    assertEquals("aim_assist", LabelKey.canonical("  aim..assist  "))
    assertEquals("aim_assist_tail", LabelKey.canonical("aim_assist______tail"))
    assertEquals("redaim_red", LabelKey.canonical("<red>Aim</red>"))
    assertEquals("unattributed", LabelKey.canonical("_unattributed"))
    assertNull(LabelKey.canonical("!!!"))
  }

  @Test
  fun `an overlong name carries the low sixteen bits of its own hash`() {
    assertEquals(
      "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk_cc20",
      LabelKey.canonical("k".repeat(70)),
      "the suffix is java string hashCode of the canonical form, masked to 0xffff, lower hex",
    )
    assertEquals(
      "killaura_with_a_really_long_descriptive_name_that_the_backe_50cf",
      LabelKey.canonical(
        "killaura_with_a_really_long_descriptive_name_that_the_backend_invented_for_it"
      ),
    )
    assertEquals(
      LabelKey.canonical(
        "killaura_with_a_really_long_descriptive_name_that_the_backend_invented_for_it"
      ),
      LabelKey.canonical(
        "KillAura With A Really Long Descriptive Name That The Backend Invented For It"
      ),
      "the hash is taken after canonicalising, so spelling variants stay one label",
    )
  }

  @Test
  fun `a truncation that lands on an underscore gives a key shorter than the cap`() {
    val key = LabelKey.canonical("a".repeat(58) + "_" + "b".repeat(20))

    assertEquals("a".repeat(58) + "_19bf", key)
    assertEquals(63, key!!.length, "trimming the underscore before the suffix costs a character")
  }
}
