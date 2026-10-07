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

import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.Schedule
import ac.shard.ai.stream.StreamProfile
import ac.shard.ai.stream.TEST_BUFFER
import ac.shard.ai.stream.roles
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class LabelCatalogTest {

  private fun catalog(
    local: Map<String, String> = emptyMap(),
    fromServer: Map<String, String> = emptyMap(),
    profile: StreamProfile? = null,
  ) = LabelCatalog(local = { local }, fromServer = { fromServer }, profile = { profile })

  private fun model(id: String, short: String?, titles: Map<String, String>) =
    ModelSpec(
      id,
      "Model " + id.uppercase(),
      short,
      Schedule.ATTACK,
      10,
      10,
      10,
      0,
      0,
      setOf(0),
      titles.keys.toList(),
      LabelMode.MULTI_LABEL,
      emptySet(),
      titles,
      emptyMap(),
      TEST_BUFFER,
      1.0,
      roles(),
    )

  private val primary = model("a", null, mapOf("x" to "Ex"))
  private val second = model("c", "C", mapOf("x" to "Ex", "y" to "Why"))
  private val profile =
    StreamProfile(1, emptyList(), 1, 512, listOf(primary, second), primary, 0, "")

  @Test
  fun `a label of a second model reads as the model and the label`() {
    val catalog = catalog(fromServer = primary.labelTitles, profile = profile)

    assertEquals("C Ex", catalog.displayName("c/x"))
    assertEquals("C Why", catalog.displayName("c/y"))
    assertEquals("C z", catalog.displayName("c/z"), "an unnamed label keeps its key")
  }

  @Test
  fun `the fallback bucket of a second model reads as the model itself`() {
    val catalog = catalog(profile = profile)

    assertEquals("C", catalog.displayName("c/" + LabelKey.UNATTRIBUTED))
  }

  @Test
  fun `an address of the primary reads like a bare label`() {
    val catalog =
      catalog(local = mapOf("x" to "Mine"), fromServer = primary.labelTitles, profile = profile)

    assertEquals("Mine", catalog.displayName("a/x"))
    assertEquals("AI (Mine, C Why)", catalog.decorate("AI", listOf("a/x", "c/y")))
  }

  @Test
  fun `the fallback bucket hides under the primary address and shows under a second model`() {
    val catalog = catalog(profile = profile)
    val bucket = LabelKey.UNATTRIBUTED

    assertEquals("AI", catalog.decorate("AI", listOf("a/$bucket")))
    assertEquals("AI (C)", catalog.decorate("AI", listOf("c/$bucket")))
    assertEquals("AI", catalog.decorate("AI", listOf("q/$bucket")), "a model that left the profile")
  }

  @Test
  fun `an address of a model the profile no longer lists is shown as stored`() {
    assertEquals("q/x", catalog(profile = profile).displayName("q/x"))
    assertEquals("c/x", catalog().displayName("c/x"))
  }

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
