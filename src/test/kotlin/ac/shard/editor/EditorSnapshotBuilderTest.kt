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

import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorSnapshotBuilderTest {

  private val apiKey = "shard_TOTALLYSECRETKEYVALUE0123456789"
  private val dbPassword = "hunter2-database-password"
  private val redisPassword = "redis-password-please-hide-me"

  private fun bundled(name: String): String =
    File(
        this::class.java.classLoader.getResource(name)?.toURI()
          ?: error("bundled $name is missing from test classpath")
      )
      .readText()

  private fun seed(dir: Path): EditorSnapshotBuilder {
    val config =
      bundled("config.yml")
        .replace(
          """server: "https://api.shard.ac/v1/inference"""",
          """server: "https://evil.test"""",
        )
        .replace("""password: "password"""", """password: "$dbPassword"""")
        .replace("""password: ""${'"'}""", """password: "$redisPassword"""")
        .replace("locale: \"en\"", "locale: \"en\"\n\nai-key-holder: placeholder")
    dir
      .resolve("config.yml")
      .toFile()
      .writeText(config.replace("ai:\n", "ai:\n  api-key: \"$apiKey\"\n"))
    dir.resolve("monitor.yml").toFile().writeText(bundled("monitor.yml"))
    dir.resolve("mitigations.yml").toFile().writeText(bundled("mitigations.yml"))
    return EditorSnapshotBuilder(dir.toFile())
  }

  @Test
  fun `no secret ever reaches the snapshot`(@TempDir dir: Path) {
    val snapshot = seed(dir).build()

    val everything = snapshot.toString()
    assertFalse(everything.contains(apiKey), "the api key must never leave the server")
    assertFalse(everything.contains(dbPassword), "the database password must never leave")
    assertFalse(everything.contains(redisPassword), "the redis password must never leave")
    assertFalse(everything.contains("evil.test"), "the inference url must never leave")
  }

  @Test
  fun `the regions already excluded are offered so the panel cannot wipe them`(@TempDir dir: Path) {
    val builder = seed(dir)
    val config = dir.resolve("config.yml").toFile()
    val tree = ac.shard.config.yaml.YamlPatcher.read(config)
    ac.shard.config.yaml.YamlPatcher.setStringListMap(
      tree,
      "ai/worldguard",
      "disabled-regions",
      mapOf("world" to listOf("spawn", "arena")),
    )
    config.writeText(ac.shard.config.yaml.YamlPatcher.render(tree))

    val snapshot = builder.build()

    assertEquals(mapOf("world" to listOf("spawn", "arena")), snapshot.disabledRegions)
  }

  @Test
  fun `a config with no regions offers an empty map, not a missing one`(@TempDir dir: Path) {
    assertEquals(emptyMap(), seed(dir).build().disabledRegions)
  }

  @Test
  fun `the punishment groups already on the server are offered`(@TempDir dir: Path) {
    val builder = seed(dir)
    dir.resolve("punishments.yml").toFile().writeText(bundled("punishments.yml"))

    val groups = builder.build().punishments

    assertEquals(setOf("AI"), groups.keys)
    assertEquals(
      listOf("[alert]", "[log]"),
      groups.getValue("AI").getValue("1").take(2),
      "the panel must see what is already configured, or saving would wipe it",
    )
    assertTrue(groups.getValue("AI").keys.containsAll(setOf("1", "3", "30")))
  }

  @Test
  fun `no punishments file means an empty map rather than a guess`(@TempDir dir: Path) {
    assertEquals(emptyMap(), seed(dir).build().punishments)
  }

  @Test
  fun `only paths the schema allows are offered`(@TempDir dir: Path) {
    val snapshot = seed(dir).build()

    snapshot.files.forEach { file ->
      val allowed = EditorSchema.editablePaths(file.name)
      file.fields.forEach { field ->
        assertTrue(field.path in allowed, "${file.name}:${field.path} is not editable")
      }
    }
  }

  @Test
  fun `every editable mitigation path is readable in the shipped file`(@TempDir dir: Path) {
    val mitigations = seed(dir).build().files.single { it.name == "mitigations.yml" }
    val offered = mitigations.fields.associate { it.path to it.value }

    assertEquals(
      EditorSchema.editablePaths("mitigations.yml"),
      offered.keys,
      "a path the schema allows but the file does not expose is dead weight in the panel",
    )
    offered.forEach { (path, value) ->
      assertEquals(
        Verdict.Allowed,
        EditorSchema.check("mitigations.yml", path, value),
        "$path is $value in the shipped file but the schema refuses it",
      )
    }
  }

  @Test
  fun `the values offered are the ones in the file`(@TempDir dir: Path) {
    val snapshot = seed(dir).build()
    val config = snapshot.files.single { it.name == "config.yml" }

    assertEquals("50.0", config.fields.single { it.path == "ai/buffer/flag" }.value)
    assertEquals("skip-detection", config.fields.single { it.path == "ai/worldguard/mode" }.value)
    assertEquals("\"en\"", config.fields.single { it.path == "locale" }.value)
  }

  @Test
  fun `the baseline moves when the file moves`(@TempDir dir: Path) {
    val builder = seed(dir)
    val before = builder.build().files.single { it.name == "config.yml" }.baseline

    dir.resolve("config.yml").toFile().appendText("\n# a human edited this\n")

    val after = builder.build().files.single { it.name == "config.yml" }.baseline
    assertNotEquals(before, after)
    assertTrue(before.startsWith("sha256:"))
  }

  @Test
  fun `a file that is not there is left out rather than faked`(@TempDir dir: Path) {
    dir.resolve("config.yml").toFile().writeText(bundled("config.yml"))

    val snapshot = EditorSnapshotBuilder(dir.toFile()).build()

    assertEquals(listOf("config.yml"), snapshot.files.map { it.name })
  }

  @Test
  fun `a key missing from the file is simply not offered`(@TempDir dir: Path) {
    dir.resolve("config.yml").toFile().writeText("ai:\n  enabled: true\n")

    val snapshot = EditorSnapshotBuilder(dir.toFile()).build(listOf("config.yml"))

    val paths = snapshot.files.single().fields.map { it.path }
    assertEquals(listOf("ai/enabled"), paths)
  }
}
