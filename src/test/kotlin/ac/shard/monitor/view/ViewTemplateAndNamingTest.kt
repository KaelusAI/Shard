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
package ac.shard.monitor.view

import ac.shard.monitor.core.fillTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewTemplateAndNamingTest {
  @Test
  fun `unknown placeholder is left literal`() {
    assertEquals("[{oops}]", fillTemplate("[{oops}]", mapOf("prob" to "50")))
  }

  @Test
  fun `repeated placeholder is replaced everywhere`() {
    assertEquals("50/50", fillTemplate("{prob}/{prob}", mapOf("prob" to "50")))
  }

  @Test
  fun `adjacent placeholders do not bleed into each other`() {
    val values = mapOf("prob" to "50", "buffer" to "7")

    assertEquals("507", fillTemplate("{prob}{buffer}", values))
  }

  @Test
  fun `a substituted value is rescanned by later keys`() {
    val values = linkedMapOf("prob" to "{buffer}", "buffer" to "7")

    assertEquals("7", fillTemplate("{prob}", values))
    assertEquals("{buffer}", fillTemplate("{prob}", linkedMapOf("prob" to "{buffer}")))
  }

  @Test
  fun `objective name is derived from the viewer and team name from the target`() {
    val id = UUID.fromString("11111111-2222-3333-4444-555555555555")

    assertEquals(OBJECTIVE_PREFIX + "111111112222", objectiveNameForViewer(id))
    assertEquals(TEAM_PREFIX + "111111112222", teamNameForView(id))
    assertNotEquals(objectiveNameForViewer(id), teamNameForView(id))
  }

  @Test
  fun `derived names stay within the vanilla length limit`() {
    val id = UUID.randomUUID()

    assertTrue(objectiveNameForViewer(id).length <= 16)
    assertTrue(teamNameForView(id).length <= 16)
  }

  @Test
  fun `placement parsing accepts both below spellings and defaults to above`() {
    assertEquals(ViewPlacement.BELOW_NAME, parseViewPlacement("below"))
    assertEquals(ViewPlacement.BELOW_NAME, parseViewPlacement("BELOW_NAME"))
    assertEquals(ViewPlacement.ABOVE_NAME, parseViewPlacement("above"))
    assertEquals(ViewPlacement.ABOVE_NAME, parseViewPlacement(null))
    assertEquals(ViewPlacement.ABOVE_NAME, parseViewPlacement("nonsense"))
  }
}
