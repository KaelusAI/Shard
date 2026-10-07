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
        Delta(
          mapOf(
            "config.yml" to
              listOf(Change("ai/persistent-buffer/decay-rate-per-hour", "2.0", "20.0"))
          )
        )
      )
    val tighter =
      EditorDiff.rows(
        Delta(
          mapOf(
            "config.yml" to listOf(Change("ai/persistent-buffer/decay-rate-per-hour", "2.0", "1.0"))
          )
        )
      )
    val off =
      EditorDiff.rows(Delta(mapOf("config.yml" to listOf(Change("ai/enabled", "true", "false")))))

    assertTrue(EditorDiff.needsConfirming(looser), "a faster decay forgets cheaters sooner")
    assertFalse(EditorDiff.needsConfirming(tighter))
    assertTrue(EditorDiff.needsConfirming(off))
  }

  @Test
  fun `loosening is judged per key, because the direction is not the same for all`() {
    fun notable(path: String, was: String, now: String) =
      EditorDiff.needsConfirming(
        EditorDiff.rows(Delta(mapOf("config.yml" to listOf(Change(path, was, now)))))
      )

    assertTrue(notable("ai/persistent-buffer/decay-rate-per-hour", "2.0", "5.0"), "faster decay")
    assertFalse(notable("ai/persistent-buffer/decay-rate-per-hour", "2.0", "1.0"))
    assertTrue(notable("ai/persistent-buffer/ttl-hours", "48", "10"), "a shorter memory is weaker")
    assertFalse(notable("ai/persistent-buffer/ttl-hours", "48", "100"))
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
