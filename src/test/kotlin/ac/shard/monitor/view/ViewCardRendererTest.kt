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

import ac.shard.ai.label.LabelCatalog
import ac.shard.detection.CardRow
import ac.shard.detection.ModelCard
import ac.shard.monitor.core.MonitorSample
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewCardRendererTest {
  private val renderer =
    ViewCardRenderer(LabelCatalog(local = { emptyMap() }, fromServer = { mapOf("x" to "X Title") }))
  private val config =
    ViewCardConfig(
      header = "N {name}",
      model = "M {model}",
      row = "R {label} {prob} {buffer}",
      singleRow = "S {label} {prob}",
      bar = "B {bar} {buffer}/{flag}",
      unavailable = "none",
      calm = "c",
      warn = "w",
      flagged = "f",
      empty = "e",
      cells = 4,
    )

  private fun sample(models: List<ModelCard>, active: Boolean = true) =
    MonitorSample(
      targetId = UUID.randomUUID(),
      targetName = "Steve",
      dataPresent = true,
      aiActive = active,
      probability = 0.0,
      buffer = 0.0,
      rawPing = 0,
      damageMultiplier = 1.0,
      prob90 = 0,
      models = models,
    )

  private fun lines(models: List<ModelCard>, active: Boolean = true) =
    renderer.render(sample(models, active), mapOf("name" to "Steve"), config).split("<newline>")

  @Test
  fun `one model draws its rows and a bar without a section title`() {
    val out = lines(listOf(ModelCard("a", 50.0, 31.4, listOf(CardRow("x", 31.4, 0.72)))))
    assertEquals(
      listOf("N Steve", "S X Title 72", "B <color:w>▰▰</color><color:e>▱▱</color> 31/50"),
      out,
    )
  }

  @Test
  fun `a model with several labels keeps the buffer on each row`() {
    val rows = listOf(CardRow("x", 31.4, 0.72), CardRow("y", 5.0, 0.3))
    val out = lines(listOf(ModelCard("a", 50.0, 31.4, rows)))
    assertEquals("R X Title 72 31.4", out[1])
    assertEquals(4, out.size)
  }

  @Test
  fun `several models each get a titled section`() {
    val out =
      lines(
        listOf(
          ModelCard("a", 50.0, 10.0, listOf(CardRow("x", 10.0, 0.4))),
          ModelCard("b", 20.0, 20.0, listOf(CardRow("b/_unattributed", 20.0, 0.95))),
        )
      )
    assertEquals("M a", out[1])
    assertEquals("M b", out[4])
    assertEquals("S  95", out[5], "an unattributed head has no label to name")
    assertEquals("B <color:f>▰▰▰▰</color> 20/20", out[6], "a full buffer is drawn as flagged")
    assertTrue(
      out[3].startsWith("B <color:e>▱▱▱▱</color>"),
      "a calm buffer under one cell is empty",
    )
  }

  @Test
  fun `no data replaces the sections and keeps the header`() {
    assertEquals(listOf("N Steve", "none"), lines(emptyList(), active = false))
    assertFalse(lines(emptyList()).any { it.startsWith("B ") })
  }
}
