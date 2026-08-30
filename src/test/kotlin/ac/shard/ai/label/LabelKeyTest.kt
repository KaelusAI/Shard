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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LabelKeyTest {
  @Test
  fun `a truncated key that lands on an underscore is still stable across a reload`() {
    val raw = "a".repeat(58) + "_" + "b".repeat(20)

    val once = LabelKey.canonical(raw)!!
    val twice = LabelKey.canonical(once)!!

    assertEquals(
      once,
      twice,
      "the plugin canonicalises model.yml again on every reload, and a key that shifts there " +
        "drops the live buffer without decay and moves the config fingerprint",
    )
  }

  @Test
  fun `spelling variants of the same label land on one key`() {
    val keys =
      listOf("Aim Assist", "aim-assist", "AIM_ASSIST", " aim  assist ", "aim.assist").map {
        LabelKey.canonical(it)
      }

    assertEquals(setOf("aim_assist"), keys.toSet())
  }

  @Test
  fun `a name that carries nothing usable becomes no key at all`() {
    assertNull(LabelKey.canonical(""))
    assertNull(LabelKey.canonical("   "))
    assertNull(LabelKey.canonical("!!!"))
    assertNull(LabelKey.canonical("___"))
  }

  @Test
  fun `MiniMessage markup cannot survive into a key`() {
    assertEquals("redaim", LabelKey.canonical("<red>aim"))
    assertEquals("click_run_command_op_evil", LabelKey.canonical("<click:run_command:'/op evil'>"))
  }

  @Test
  fun `the server cannot claim the reserved namespace`() {
    assertEquals("unattributed", LabelKey.canonical("_unattributed"))
    assertEquals("aim", LabelKey.canonical("__aim__"))
    assertTrue(LabelKey.isReserved(LabelKey.UNATTRIBUTED))
    assertTrue(!LabelKey.isReserved(LabelKey.canonical("_unattributed")!!))
  }

  @Test
  fun `an overlong name fits the column and stays distinguishable`() {
    val shared = "a".repeat(80)
    val first = LabelKey.canonical(shared + "one")!!
    val second = LabelKey.canonical(shared + "two")!!

    assertEquals(LabelKey.MAX_LENGTH, first.length)
    assertEquals(LabelKey.MAX_LENGTH, second.length)
    assertNotEquals(first, second, "two long names sharing a prefix must not collapse into one key")
    assertEquals(first, LabelKey.canonical(shared + "one"), "the key has to be stable")
  }

  @Test
  fun `canonicalising a key again gives the same key`() {
    val inputs =
      listOf("aim", "Aim Assist", "a".repeat(200), "  trigger-bot  ", "_unattributed", "<red>aim")

    for (raw in inputs) {
      val once = LabelKey.canonical(raw) ?: continue
      assertEquals(once, LabelKey.canonical(once), "a second pass changed the key for $raw")
    }
  }

  @Test
  fun `two spellings of one overlong name land on the same key`() {
    val long = "a".repeat(70)

    assertEquals(LabelKey.canonical(long), LabelKey.canonical(long.uppercase()))
  }

  @Test
  fun `a list keeps its order because probabilities are matched by position`() {
    val keys = LabelKey.canonicalList(listOf("Trigger", "Aim", "Reach"))

    assertEquals(listOf("trigger", "aim", "reach"), keys)
  }

  @Test
  fun `duplicates collapse and are reported once`() {
    val collapsed = mutableListOf<String>()

    val keys = LabelKey.canonicalList(listOf("aim", "Aim", "AIM")) { raw, _ -> collapsed += raw }

    assertEquals(listOf("aim"), keys)
    assertEquals(listOf("Aim", "AIM"), collapsed)
  }

  @Test
  fun `unusable names drop out of a list without breaking the rest`() {
    val keys = LabelKey.canonicalList(listOf("aim", "  ", "trigger"))

    assertEquals(listOf("aim", "trigger"), keys)
  }
}
