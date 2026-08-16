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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class YamlPatcherTest {

  private fun bundled(name: String): File =
    File(
      this::class.java.classLoader.getResource(name)?.toURI()
        ?: error("bundled $name is missing from test classpath")
    )

  private fun patched(dir: Path, yaml: String, path: String, value: String): String {
    val file = dir.resolve("sample.yml")
    file.writeText(yaml)
    val tree = YamlPatcher.read(file.toFile())
    assertIs<PatchResult.Applied>(YamlPatcher.setScalar(tree, path, value))
    return YamlPatcher.render(tree)
  }

  @ParameterizedTest
  @ValueSource(strings = ["config.yml", "punishments.yml", "monitor.yml"])
  fun `reading and writing a shipped file changes not one byte`(name: String) {
    val original = bundled(name).readText()

    val rewritten = YamlPatcher.render(YamlPatcher.read(bundled(name)))

    assertEquals(original, rewritten, "$name must survive a read/write round trip untouched")
  }

  @Test
  fun `a replaced value keeps the space after the colon`(@TempDir dir: Path) {
    val out = patched(dir, "ai:\n  buffer:\n    flag: 50.0\n", "ai/buffer/flag", "8.0")

    assertContains(out, "    flag: 8.0")
    assertFalse(out.contains("flag:8.0"), "a missing space produces YAML no reader accepts")
  }

  @Test
  fun `a replaced value keeps the comment sitting after it`(@TempDir dir: Path) {
    val out =
      patched(
        dir,
        "ai:\n  buffer:\n    flag: 50.0   # how much is too much\n",
        "ai/buffer/flag",
        "8.0",
      )

    assertContains(out, "    flag: 8.0   # how much is too much")
  }

  @Test
  fun `a hash inside quotes is part of the value, not a comment`(@TempDir dir: Path) {
    val out = patched(dir, "theme:\n  colour: \"#ff0000\"  # red\n", "theme/colour", "\"#00ff00\"")

    assertContains(out, """  colour: "#00ff00"  # red""")
  }

  @Test
  fun `the surrounding file is left alone`(@TempDir dir: Path) {
    val source =
      """
      # top comment
      ai:
        # what the buffer means
        buffer:
          flag: 50.0
          decrease: 0.25   # per calm answer

      other:
        untouched: true
      """
        .trimIndent() + "\n"

    val out = patched(dir, source, "ai/buffer/flag", "8.0")

    assertEquals(source.replace("flag: 50.0", "flag: 8.0"), out)
  }

  @Test
  fun `a path that holds a section is refused`(@TempDir dir: Path) {
    val file = dir.resolve("sample.yml")
    file.writeText("ai:\n  buffer:\n    flag: 50.0\n")
    val tree = YamlPatcher.read(file.toFile())

    assertIs<PatchResult.Unsupported>(YamlPatcher.setScalar(tree, "ai/buffer", "8.0"))
  }

  @Test
  fun `a path that is not there is reported as missing`() {
    val tree = YamlPatcher.read(bundled("config.yml"))

    assertIs<PatchResult.NotFound>(
      YamlPatcher.setScalar(tree, "ai/worldguard/disabled-regions", "x")
    )
  }

  @Test
  fun `lookups are case sensitive`() {
    val tree = YamlPatcher.read(bundled("punishments.yml"))

    assertTrue(tree.find("Punishments") != null, "the file spells it with a capital P")
    assertTrue(tree.find("punishments") == null, "a lowercase path must not resolve")
  }
}
