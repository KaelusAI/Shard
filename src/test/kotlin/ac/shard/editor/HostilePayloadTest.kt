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
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class HostilePayloadTest {

  private fun bundled(name: String): String =
    File(
        this::class.java.classLoader.getResource(name)?.toURI()
          ?: error("bundled $name is missing from test classpath")
      )
      .readText()

  private fun world(dir: Path): EditorApply {
    listOf("config.yml", "monitor.yml", "punishments.yml").forEach {
      dir.resolve(it).toFile().writeText(bundled(it))
    }
    val store =
      YamlFileStore(dir.toFile(), mockk(relaxed = true)) { Instant.parse("2026-08-10T12:30:00Z") }
    return EditorApply(dir.toFile(), store)
  }

  private fun untouched(dir: Path) {
    listOf("config.yml", "monitor.yml", "punishments.yml").forEach {
      assertEquals(bundled(it), dir.resolve(it).toFile().readText(), "$it must be untouched")
    }
  }

  @Test
  fun `a punishment step at zero or below is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    listOf("0", "-1", "-999").forEach { level ->
      assertIs<ApplyResult.Refused>(
        apply.apply(
          Delta(punishments = listOf(PunishmentEdit("AI", mapOf(level to listOf("[alert]")))))
        ),
        "step $level should have been refused",
      )
    }
    untouched(dir)
  }

  @Test
  fun `a punishment group of a hundred steps is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    val huge = (1..120).associate { "$it" to listOf("[alert]") }

    assertIs<ApplyResult.Refused>(
      apply.apply(Delta(punishments = listOf(PunishmentEdit("AI", huge))))
    )
    untouched(dir)
  }

  @Test
  fun `an empty world name or an empty region is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    listOf(
        mapOf("" to listOf("spawn")),
        mapOf("world" to listOf("")),
        mapOf("" to listOf("")),
        mapOf(" " to listOf("spawn")),
      )
      .forEach { entries ->
        assertIs<ApplyResult.Refused>(
          apply.apply(Delta(disabledRegions = entries)),
          "should have refused $entries",
        )
      }
    untouched(dir)
  }

  @Test
  fun `a value that would break the file never survives`(@TempDir dir: Path) {
    val apply = world(dir)

    listOf(
        """"PvP" then: evil""",
        "\"unclosed",
        "PvP\nnetwork:\n  enabled: true",
        "[unclosed",
      )
      .forEach { hostile ->
        val result =
          apply.apply(
            Delta(mapOf("config.yml" to listOf(Change("network/name", "\"server-1\"", hostile))))
          )
        assertIs<ApplyResult.Refused>(result, "should have refused: $hostile")
      }
    untouched(dir)

    val stillReads =
      YamlConfigurationLoader.builder().path(dir.resolve("config.yml")).build().load()
    assertEquals("server-1", stillReads.node("network", "name").getString(""))
  }

  @Test
  fun `two results in a row apply only the one that was confirmed`(@TempDir dir: Path) {
    val apply = world(dir)

    val first =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "80.0")))))
    assertIs<ApplyResult.Applied>(first)

    val stale =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "90.0")))))

    assertIs<ApplyResult.Refused>(stale, "the second result was built on the old reading")
    assertEquals(
      bundled("config.yml").replace("flag: 50.0", "flag: 80.0"),
      dir.resolve("config.yml").toFile().readText(),
    )
  }
}
