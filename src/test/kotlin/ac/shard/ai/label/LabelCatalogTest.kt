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

class LabelCatalogTest {

  private fun catalog(
    local: Map<String, String> = emptyMap(),
    fromServer: Map<String, String> = emptyMap(),
  ) = LabelCatalog(local = { local }, fromServer = { fromServer })

  @Test
  fun `the model names its own heads and the plugin shows exactly that`() {
    val catalog = catalog(fromServer = mapOf("aim" to "Aim Assist", "trigger" to "Auto Clicker"))

    assertEquals("Aim Assist", catalog.displayName("aim"))
    assertEquals("Auto Clicker", catalog.displayName("trigger"))
  }

  @Test
  fun `a label nobody named shows its own key rather than an invented name`() {
    val catalog = catalog()

    assertEquals("aim_assist", catalog.displayName("aim_assist"))
    assertEquals("triggerbot", catalog.displayName("triggerbot"))
  }

  @Test
  fun `the admin has the last word over the model`() {
    val catalog =
      catalog(
        local = mapOf("trigger" to "Auto Clicker"),
        fromServer = mapOf("trigger" to "Trigger"),
      )

    assertEquals("Auto Clicker", catalog.displayName("trigger"))
  }

  @Test
  fun `a name written under a differently spelled key still matches`() {
    val catalog = catalog(local = mapOf("aim_assist" to "Assist"))

    assertEquals("Assist", catalog.displayName("Aim Assist"))
  }

  @Test
  fun `a blank name falls through to the next source instead of showing nothing`() {
    val catalog = catalog(local = mapOf("aim" to "   "), fromServer = mapOf("aim" to "Aim Assist"))

    assertEquals("Aim Assist", catalog.displayName("aim"))
    assertEquals("trigger", catalog(local = mapOf("trigger" to " ")).displayName("trigger"))
  }

  @Test
  fun `the check name carries its labels`() {
    val catalog = catalog(fromServer = mapOf("aim" to "Aim", "trigger" to "Trigger"))

    assertEquals("AI (Aim)", catalog.decorate("AI", listOf("aim")))
    assertEquals("AI (Aim, Trigger)", catalog.decorate("AI", listOf("aim", "trigger")))
  }

  @Test
  fun `a verdict with no labels leaves the check name alone`() {
    val catalog = catalog()

    assertEquals("AI", catalog.decorate("AI", emptyList()))
    assertEquals("AI", catalog.decorate("AI", listOf(LabelKey.UNATTRIBUTED)))
  }

  @Test
  fun `service labels stay out of anything a person reads`() {
    val catalog = catalog(fromServer = mapOf("aim" to "Aim"))
    val buffers = mapOf(LabelKey.UNATTRIBUTED to 40.0, "aim" to 12.0)

    assertEquals(listOf("aim"), catalog.visible(buffers))
    assertEquals("aim", catalog.leading(buffers))
    assertEquals("Aim", catalog.format(buffers.keys))
  }

  @Test
  fun `labels survive the trip through storage and back onto the screen`() {
    val catalog = catalog(fromServer = mapOf("aim" to "Aim", "trigger" to "Trigger"))
    val crossed = setOf("aim", "trigger")

    val stored = crossed.joinToString(",")
    val readBack = stored.split(',').map(String::trim).filter(String::isNotEmpty)

    assertEquals("AI (Aim, Trigger)", catalog.decorate("AI", readBack))
  }

  @Test
  fun `a stored row from a single-headed model shows no label at all`() {
    val catalog = catalog()

    val readBack = LabelKey.UNATTRIBUTED.split(',').filter(String::isNotEmpty)

    assertEquals("AI", catalog.decorate("AI", readBack))
  }

  @Test
  fun `the leading label is the one with the highest buffer`() {
    val catalog = catalog()

    assertEquals("trigger", catalog.leading(mapOf("aim" to 12.0, "trigger" to 41.0)))
    assertNull(catalog.leading(mapOf("aim" to 0.0)), "a buffer at zero leads nothing")
    assertNull(catalog.leading(emptyMap()))
  }

  @Test
  fun `visible labels come back strongest first`() {
    val catalog = catalog()

    assertEquals(
      listOf("trigger", "aim", "reach"),
      catalog.visible(mapOf("aim" to 12.0, "reach" to 1.0, "trigger" to 41.0)),
    )
  }
}
