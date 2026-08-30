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

import ac.shard.ai.label.LabelKey
import ac.shard.checks.ICheck
import io.mockk.every
import io.mockk.mockk
import java.util.TreeMap
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PunishGroupLabelMatchingTest {

  private val aiCheck =
    mockk<ICheck>().also {
      every { it.checkName } returns "AI"
      every { it.legacyCheckNames } returns emptyList()
    }

  private fun group(vararg labels: String) =
    PunishGroup("test", listOf("AI"), labels.toList(), TreeMap(mapOf(1 to listOf("[alert]"))))

  @Test
  fun `a filter written by hand matches the key the model sends`() {
    val configured = group("Aim Assist")

    assertTrue(configured.matches(aiCheck, setOf("aim_assist")))
    assertTrue(group("aim-assist").matches(aiCheck, setOf("aim_assist")))
    assertTrue(group("AIM_ASSIST").matches(aiCheck, setOf("aim_assist")))
  }

  @Test
  fun `a group without a filter catches every label, including one nobody listed`() {
    val catchAll = group()

    assertTrue(catchAll.matches(aiCheck, setOf("aim")))
    assertTrue(catchAll.matches(aiCheck, setOf("a_label_shipped_after_this_config_was_written")))
    assertTrue(catchAll.matches(aiCheck, setOf(LabelKey.UNATTRIBUTED)))
    assertTrue(catchAll.matches(aiCheck, emptySet()))
  }

  @Test
  fun `a filtered group ignores a label it does not list`() {
    val aimOnly = group("aim")

    assertTrue(aimOnly.matches(aiCheck, setOf("aim", "trigger")))
    assertFalse(aimOnly.matches(aiCheck, setOf("trigger")))
    assertFalse(
      aimOnly.matches(aiCheck, setOf(LabelKey.UNATTRIBUTED)),
      "a single-headed model must not slip through a label filter",
    )
  }

  @Test
  fun `a filter that survives no normalisation never matches instead of matching everything`() {
    val nonsense = group("!!!")

    assertTrue(nonsense.associatedLabels.isEmpty())
    assertTrue(nonsense.matches(aiCheck, setOf("aim")), "an empty filter is a catch-all by design")
  }
}
