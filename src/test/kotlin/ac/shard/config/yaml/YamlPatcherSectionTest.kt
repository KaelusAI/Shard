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
package ac.shard.config.yaml

import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class YamlPatcherSectionTest {

  private fun bundledConfig(): File =
    File(
      this::class.java.classLoader.getResource("config.yml")?.toURI()
        ?: error("bundled config.yml is missing from test classpath")
    )

  private fun parses(dir: Path, body: String): Boolean {
    val file = dir.resolve("out.yml")
    file.writeText(body)
    return runCatching { YamlConfigurationLoader.builder().path(file).build().load() }.isSuccess
  }

  @Test
  fun `a section the shipped file does not carry is created`(@TempDir dir: Path) {
    val tree = YamlPatcher.read(bundledConfig())
    assertEquals(null, tree.find("ai/worldguard/disabled-regions"))

    val result =
      YamlPatcher.setStringListMap(
        tree,
        "ai/worldguard",
        "disabled-regions",
        mapOf("world" to listOf("spawn", "arena"), "world_nether" to listOf("nether_hub")),
      )

    assertIs<PatchResult.Applied>(result)
    val out = YamlPatcher.render(tree)
    assertContains(out, "    disabled-regions:")
    assertContains(out, "      world:")
    assertContains(out, "        - \"spawn\"")
    assertContains(out, "        - \"arena\"")
    assertContains(out, "      world_nether:")
    assertEquals(true, parses(dir, out), "the written file must still be readable")
  }

  @Test
  fun `a list item keeps the space after the dash`(@TempDir dir: Path) {
    val tree = YamlPatcher.read(bundledConfig())

    YamlPatcher.setStringListMap(
      tree,
      "ai/worldguard",
      "disabled-regions",
      mapOf("world" to listOf("spawn")),
    )

    val out = YamlPatcher.render(tree)
    assertEquals(false, out.contains("-\"spawn\""), "a missing space breaks every reader")
    assertEquals(true, parses(dir, out))
  }

  @Test
  fun `the section reads back as what was written`(@TempDir dir: Path) {
    val tree = YamlPatcher.read(bundledConfig())
    val entries = mapOf("world" to listOf("spawn", "arena"), "*" to listOf("__global__"))

    YamlPatcher.setStringListMap(tree, "ai/worldguard", "disabled-regions", entries)
    val file = dir.resolve("again.yml")
    file.writeText(YamlPatcher.render(tree))

    val reread = YamlPatcher.read(file.toFile())
    assertEquals(entries, YamlPatcher.readStringListMap(reread, "ai/worldguard/disabled-regions"))
  }

  @Test
  fun `an existing section is replaced, not appended to`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml")
    file.writeText(
      "ai:\n  worldguard:\n    mode: skip-detection\n    disabled-regions:\n      world:\n" +
        "        - \"old\"\n"
    )
    val tree = YamlPatcher.read(file.toFile())

    YamlPatcher.setStringListMap(
      tree,
      "ai/worldguard",
      "disabled-regions",
      mapOf("world" to listOf("new")),
    )

    val out = YamlPatcher.render(tree)
    assertContains(out, "- \"new\"")
    assertEquals(false, out.contains("\"old\""), "the previous list must be gone")
    assertEquals(true, parses(dir, out))
  }

  @Test
  fun `a quote inside a region name cannot break out of the string`(@TempDir dir: Path) {
    val tree = YamlPatcher.read(bundledConfig())

    YamlPatcher.setStringListMap(
      tree,
      "ai/worldguard",
      "disabled-regions",
      mapOf("world" to listOf("""spawn" then: evil""")),
    )

    val out = YamlPatcher.render(tree)
    assertEquals(true, parses(dir, out), "an escaped quote must not produce a second key")
    assertContains(out, """\"""")
  }

  @Test
  fun `a parent that is not there is reported`() {
    val tree = YamlPatcher.read(bundledConfig())

    assertIs<PatchResult.NotFound>(
      YamlPatcher.setStringListMap(tree, "ai/nowhere", "disabled-regions", mapOf())
    )
  }
}
