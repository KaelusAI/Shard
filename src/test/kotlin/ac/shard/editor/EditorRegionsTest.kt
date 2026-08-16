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

import ac.shard.config.yaml.YamlFileStore
import ac.shard.config.yaml.YamlPatcher
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorRegionsTest {

  private fun bundled(name: String): String =
    File(
        this::class.java.classLoader.getResource(name)?.toURI()
          ?: error("bundled $name is missing from test classpath")
      )
      .readText()

  private fun world(dir: Path): EditorApply {
    dir.resolve("config.yml").toFile().writeText(bundled("config.yml"))
    dir.resolve("monitor.yml").toFile().writeText(bundled("monitor.yml"))
    val store =
      YamlFileStore(dir.toFile(), mockk(relaxed = true)) { Instant.parse("2026-08-10T12:30:00Z") }
    return EditorApply(dir.toFile(), store)
  }

  @Test
  fun `the wizard can write regions into a config that never had the key`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(disabledRegions = mapOf("world" to listOf("spawn", "arena"), "*" to listOf("hub")))
      )

    assertIs<ApplyResult.Applied>(result)
    val written = dir.resolve("config.yml").toFile()
    assertContains(written.readText(), "        - \"spawn\"")
    assertEquals(
      mapOf("world" to listOf("spawn", "arena"), "*" to listOf("hub")),
      YamlPatcher.readStringListMap(
        YamlPatcher.read(written),
        "ai/worldguard/disabled-regions",
      ),
    )
  }

  @Test
  fun `regions travel together with an ordinary change`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          changes =
            mapOf(
              "config.yml" to
                listOf(Change("ai/worldguard/mode", "skip-detection", "skip-punishment"))
            ),
          disabledRegions = mapOf("world" to listOf("spawn")),
        )
      )

    assertIs<ApplyResult.Applied>(result)
    val text = dir.resolve("config.yml").toFile().readText()
    assertContains(text, "mode: skip-punishment")
    assertContains(text, "- \"spawn\"")
  }

  @Test
  fun `a region name that could be anything else is refused`(@TempDir dir: Path) {
    val before = bundled("config.yml")
    val apply = world(dir)

    listOf(
        mapOf("world" to listOf("spawn\" then: evil")),
        mapOf("world" to listOf("../../etc/passwd")),
        mapOf("world" to listOf("region name with spaces")),
        mapOf("wor ld" to listOf("spawn")),
        mapOf("world" to listOf("")),
      )
      .forEach { entries ->
        val result = apply.apply(Delta(disabledRegions = entries))
        assertIs<ApplyResult.Refused>(result, "should have refused $entries")
      }

    assertEquals(before, dir.resolve("config.yml").toFile().readText())
  }

  @Test
  fun `the shapes worldguard actually uses are accepted`() {
    assertIs<Verdict.Allowed>(
      EditorSchema.checkRegions(
        mapOf(
          "world" to listOf("spawn", "pvp-arena", "shop_1"),
          "world_nether" to listOf("__global__"),
          "*" to listOf("lobby"),
        )
      )
    )
  }

  @Test
  fun `an absurd number of regions is refused`() {
    assertIs<Verdict.Refused>(
      EditorSchema.checkRegions(mapOf("world" to (1..600).map { "region$it" }))
    )
    assertIs<Verdict.Refused>(
      EditorSchema.checkRegions((1..200).associate { "world$it" to listOf("spawn") })
    )
  }

  @Test
  fun `an empty map clears the list`(@TempDir dir: Path) {
    val apply = world(dir)
    apply.apply(Delta(disabledRegions = mapOf("world" to listOf("spawn"))))

    val result = apply.apply(Delta(disabledRegions = emptyMap()))

    assertIs<ApplyResult.Applied>(result)
    assertEquals(
      emptyMap(),
      YamlPatcher.readStringListMap(
        YamlPatcher.read(dir.resolve("config.yml").toFile()),
        "ai/worldguard/disabled-regions",
      ),
    )
  }
}
