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

import ac.shard.checks.ICheck
import java.util.TreeMap
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PunishGroupTest {

  private class FakeCheck(
    override val checkName: String,
    override val legacyCheckNames: List<String> = emptyList(),
  ) : ICheck

  private fun group(checks: List<String>, labels: List<String> = emptyList()) =
    PunishGroup("test", checks, labels, TreeMap(mapOf(1 to listOf("[alert]"))))

  @Test
  fun `config written against the old check name keeps matching after a rename`() {
    val renamed = FakeCheck("AI", listOf("AI (Aim)"))

    assertTrue(group(listOf("AI (Aim)")).isCheckAssociated(renamed))
    assertTrue(group(listOf("AI")).isCheckAssociated(renamed))
    assertFalse(group(listOf("Reach")).isCheckAssociated(renamed))
  }

  @Test
  fun `a group without labels fires on any detection of its checks`() {
    val check = FakeCheck("AI")

    assertTrue(group(listOf("AI")).matches(check, emptySet()))
    assertTrue(group(listOf("AI")).matches(check, setOf("AUTOCRYSTAL")))
  }

  @Test
  fun `a group with labels only fires when one of them is present`() {
    val check = FakeCheck("AI")
    val crystalOnly = group(listOf("AI"), listOf("AUTOCRYSTAL"))

    assertTrue(crystalOnly.matches(check, setOf("AUTOCRYSTAL")))
    assertTrue(crystalOnly.matches(check, setOf("AIM_AURA", "AUTOCRYSTAL")))
    assertFalse(crystalOnly.matches(check, setOf("AIM_AURA")))
    assertFalse(crystalOnly.matches(check, emptySet()))
  }

  @Test
  fun `label matching ignores case on both sides`() {
    val check = FakeCheck("AI")

    assertTrue(group(listOf("ai"), listOf("autocrystal")).matches(check, setOf("AutoCrystal")))
  }
}
