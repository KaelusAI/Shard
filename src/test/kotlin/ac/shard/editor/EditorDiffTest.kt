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
package ac.shard.editor

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class EditorDiffTest {

  @Test
  fun `a change that loosens the check asks for confirming, one that tightens it does not`() {
    val looser =
      EditorDiff.rows(
        Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "500.0"))))
      )
    val tighter =
      EditorDiff.rows(
        Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "30.0"))))
      )
    val off =
      EditorDiff.rows(Delta(mapOf("config.yml" to listOf(Change("ai/enabled", "true", "false")))))

    assertTrue(EditorDiff.needsConfirming(looser), "a higher flag threshold catches fewer people")
    assertFalse(EditorDiff.needsConfirming(tighter))
    assertTrue(EditorDiff.needsConfirming(off))
  }

  @Test
  fun `a punishment group that ends in a ban asks for confirming and shows the actions in order`() {
    val rows =
      EditorDiff.rows(
        Delta(
          punishments =
            listOf(
              PunishmentEdit(
                "AI",
                mapOf(
                  "20" to listOf("[alert]", "ban <player> nope"),
                  "5" to listOf("[alert]", "[log]"),
                ),
              )
            )
        )
      )

    assertEquals(
      listOf("AI 5-19", "AI 20+"),
      rows.map { it.key },
      "a step runs until the next one, so the diff must show the span, not a single level",
    )
    assertEquals("[alert] | [log]", rows.first().after)
    assertEquals(DiffWeight.ORDINARY, rows.first().weight)
    assertEquals(DiffWeight.NOTABLE, rows.last().weight)
    assertTrue(EditorDiff.needsConfirming(rows))
  }

  @Test
  fun `loosening is judged per key, because the direction is not the same for all`() {
    fun notable(path: String, was: String, now: String) =
      EditorDiff.needsConfirming(
        EditorDiff.rows(Delta(mapOf("config.yml" to listOf(Change(path, was, now)))))
      )

    assertTrue(notable("ai/buffer/flag", "50.0", "80.0"), "a higher bar catches fewer")
    assertFalse(notable("ai/buffer/flag", "50.0", "30.0"))
    assertTrue(notable("ai/buffer/multiplier", "100.0", "10.0"), "slower growth is weaker")
    assertFalse(notable("ai/buffer/multiplier", "100.0", "200.0"))
    assertTrue(notable("ai/buffer/decrease", "0.25", "5.0"), "faster decay is weaker")
    assertTrue(
      notable("ai/worldguard/mode", "skip-punishment", "skip-detection"),
      "skip-detection stops sending windows, so the region goes unwatched",
    )
    assertFalse(notable("ai/worldguard/mode", "skip-detection", "skip-punishment"))
    assertTrue(notable("exemptions/bedrock", "false", "true"), "exempting more people is weaker")
    assertTrue(notable("ai/enabled", "true", "false"))
    assertFalse(notable("ai/batch/max-size", "32", "256"), "throughput is not detection strength")
  }

  @Test
  fun `every key called loosening is a key the schema actually accepts`() {
    listOf("config.yml", "monitor.yml", "mitigations.yml").forEach { file ->
      val editable = EditorSchema.editablePaths(file)
      EditorSchema.loosenedPaths(file).forEach {
        assertTrue(
          it in editable,
          "$file:$it is marked as loosening but is not editable - it has drifted",
        )
      }
    }
  }

  @Test
  fun `steps one after another read as single levels, the last one is open ended`() {
    val rows =
      EditorDiff.rows(
        Delta(
          punishments =
            listOf(
              PunishmentEdit(
                "AI",
                mapOf(
                  "1" to listOf("[alert]"),
                  "2" to listOf("[log]"),
                  "10" to listOf("kick <player> stop"),
                ),
              )
            )
        )
      )

    assertEquals(listOf("AI 1", "AI 2-9", "AI 10+"), rows.map { it.key })
  }

  @Test
  fun `switching the check off in regions is shown as one readable row`() {
    val rows =
      EditorDiff.rows(
        Delta(disabledRegions = mapOf("world" to listOf("spawn", "arena"), "*" to listOf("hub")))
      )

    assertEquals("*: hub; world: spawn, arena", rows.single().after)
    assertTrue(EditorDiff.needsConfirming(rows))
    assertFalse(
      EditorDiff.needsConfirming(EditorDiff.rows(Delta(disabledRegions = emptyMap()))),
      "clearing the list turns the check back on, which needs no warning",
    )
  }
}
