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

import ac.shard.config.MitigationsFile
import ac.shard.config.yaml.YamlFileStore
import ac.shard.mitigation.MitigationTier
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class EditorApplyTest {

  private fun bundled(name: String): String =
    File(
        this::class.java.classLoader.getResource(name)?.toURI()
          ?: error("bundled $name is missing from test classpath")
      )
      .readText()

  private fun digest(file: File): String =
    "sha256:" +
      java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
        "%02x".format(it)
      }

  private fun readMitigations(dir: Path) =
    MitigationsFile.read(
      YamlConfigurationLoader.builder().path(dir.resolve("mitigations.yml")).build().load(),
      mutableListOf(),
    )

  private fun world(dir: Path): EditorApply {
    dir.resolve("config.yml").toFile().writeText(bundled("config.yml"))
    dir.resolve("monitor.yml").toFile().writeText(bundled("monitor.yml"))
    dir.resolve("mitigations.yml").toFile().writeText(bundled("mitigations.yml"))
    val store =
      YamlFileStore(dir.toFile(), mockk(relaxed = true)) { Instant.parse("2026-08-10T12:30:00Z") }
    return EditorApply(dir.toFile(), store)
  }

  @Test
  fun `a change the schema allows lands and leaves the rest of the file alone`(@TempDir dir: Path) {
    val before = bundled("config.yml")
    val apply = world(dir)

    val result =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "80.0")))))

    assertIs<ApplyResult.Applied>(result)
    assertEquals(1, result.count)
    val after = dir.resolve("config.yml").toFile().readText()
    assertEquals(before.replace("flag: 50.0", "flag: 80.0"), after)
  }

  @Test
  fun `a rule set from the panel replaces the block and keeps the order it came in`(
    @TempDir dir: Path
  ) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          mitigations =
            MitigationEdit(
              rules =
                listOf(
                  mapOf(
                    "id" to "second",
                    "level" to "mid",
                    "when" to mapOf("score" to mapOf("above" to 12.0)),
                    "then" to mapOf("melee" to 0.7),
                  ),
                  mapOf(
                    "id" to "first",
                    "level" to "high",
                    "when" to mapOf("probability" to mapOf("above" to 0.95)),
                    "then" to mapOf("melee" to 0.4),
                  ),
                )
            )
        )
      )

    assertIs<ApplyResult.Applied>(result)
    val settings = readMitigations(dir)
    assertEquals(
      listOf("second", "first"),
      settings.rules.map { it.id },
      "order is strength, so it must survive the round trip untouched",
    )
    assertEquals(MitigationTier.HIGH, settings.rule("first")?.level)
  }

  @Test
  fun `a rule set that would leave the file unreadable is refused whole`(@TempDir dir: Path) {
    val before = bundled("mitigations.yml")
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          mitigations =
            MitigationEdit(
              rules =
                listOf(mapOf("id" to "broken", "level" to "nope", "when" to emptyMap<Any, Any>()))
            )
        )
      )

    assertIs<ApplyResult.Refused>(result)
    assertEquals(
      before,
      dir.resolve("mitigations.yml").toFile().readText(),
      "a refused result must not leave half a file behind",
    )
  }

  @Test
  fun `a section written while the editor was open is not overwritten silently`(
    @TempDir dir: Path
  ) {
    val apply = world(dir)
    val opened = digest(dir.resolve("mitigations.yml").toFile())

    dir.resolve("mitigations.yml").toFile().appendText("\n# an admin edited this by hand\n")
    val edited = dir.resolve("mitigations.yml").toFile().readText()

    val result =
      apply.apply(
        Delta(
          mitigations =
            MitigationEdit(
              rules =
                listOf(
                  mapOf(
                    "id" to "only",
                    "level" to "mid",
                    "when" to mapOf("score" to mapOf("above" to 9.0)),
                  )
                )
            )
        ),
        mapOf("mitigations.yml" to opened),
      )

    assertIs<ApplyResult.Refused>(result)
    assertTrue(result.reasons.single().contains("reopen"))
    assertEquals(edited, dir.resolve("mitigations.yml").toFile().readText())
  }

  @Test
  fun `an untouched file passes the same check`(@TempDir dir: Path) {
    val apply = world(dir)
    val opened = digest(dir.resolve("mitigations.yml").toFile())

    val result =
      apply.apply(
        Delta(
          mitigations =
            MitigationEdit(
              rules =
                listOf(
                  mapOf(
                    "id" to "only",
                    "level" to "mid",
                    "when" to mapOf("score" to mapOf("above" to 9.0)),
                  )
                )
            )
        ),
        mapOf("mitigations.yml" to opened),
      )

    assertIs<ApplyResult.Applied>(result)
  }

  @Test
  fun `several files move together`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          mapOf(
            "config.yml" to listOf(Change("ai/buffer/flag", "50.0", "40.0")),
            "monitor.yml" to listOf(Change("auto/exit-ratio", "0.8", "0.5")),
          )
        )
      )

    assertIs<ApplyResult.Applied>(result)
    assertEquals(2, result.count)
    assertContains(dir.resolve("config.yml").toFile().readText(), "flag: 40.0")
    assertContains(dir.resolve("monitor.yml").toFile().readText(), "exit-ratio: 0.5")
  }

  @Test
  fun `a key outside the allowlist is refused and nothing is written`(@TempDir dir: Path) {
    val before = bundled("config.yml")
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          mapOf(
            "config.yml" to
              listOf(
                Change("ai/buffer/flag", "50.0", "8.0"),
                Change("ai/api-key", "", "\"stolen\""),
              )
          )
        )
      )

    assertIs<ApplyResult.Refused>(result)
    assertEquals(before, dir.resolve("config.yml").toFile().readText())
    assertTrue(result.reasons.any { it.contains("api-key") })
    assertTrue(!File(dir.toFile(), "backups").exists(), "a refusal must not even take a backup")
  }

  @Test
  fun `a value of the wrong shape never reaches the file`(@TempDir dir: Path) {
    val before = bundled("config.yml")
    val apply = world(dir)

    val result =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/enabled", "true", "maybe")))))

    assertIs<ApplyResult.Refused>(result)
    assertEquals(before, dir.resolve("config.yml").toFile().readText())
  }

  @Test
  fun `a change built on a stale reading is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "12.0", "80.0")))))

    assertIs<ApplyResult.Refused>(result)
    assertTrue(result.reasons.single().contains("reopen the editor"))
    assertContains(dir.resolve("config.yml").toFile().readText(), "flag: 50.0")
  }

  @Test
  fun `a pair that only makes sense together is checked together`(@TempDir dir: Path) {
    val before = bundled("config.yml")
    val apply = world(dir)

    val single =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "8.0")))))

    assertIs<ApplyResult.Refused>(single)
    assertTrue(
      single.reasons.single().contains("reset-on-flag"),
      "flag 8.0 sits under the untouched reset-on-flag 25.0, which flags on every answer",
    )
    assertEquals(before, dir.resolve("config.yml").toFile().readText())

    val both =
      apply.apply(
        Delta(
          mapOf(
            "config.yml" to
              listOf(
                Change("ai/buffer/flag", "50.0", "8.0"),
                Change("ai/buffer/reset-on-flag", "25.0", "4.0"),
              )
          )
        )
      )

    assertIs<ApplyResult.Applied>(both)
  }

  @Test
  fun `a backoff that would park the check on the first failure is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(mapOf("config.yml" to listOf(Change("ai/backoff/initial-duration", "5", "3600"))))
      )

    assertIs<ApplyResult.Refused>(result)
  }

  @Test
  fun `one bad change in a batch keeps the other file untouched`(@TempDir dir: Path) {
    val beforeMonitor = bundled("monitor.yml")
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          mapOf(
            "monitor.yml" to listOf(Change("auto/exit-ratio", "0.8", "0.5")),
            "config.yml" to listOf(Change("ai/buffer/flag", "50.0", "nonsense")),
          )
        )
      )

    assertIs<ApplyResult.Refused>(result)
    assertEquals(beforeMonitor, dir.resolve("monitor.yml").toFile().readText())
  }

  @Test
  fun `a key that is not in the file is reported rather than invented`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          mapOf(
            "config.yml" to listOf(Change("ai/worldguard/flag-overrides-list", "true", "false"))
          )
        )
      )

    assertIs<ApplyResult.Applied>(result)

    dir.resolve("config.yml").toFile().writeText("ai:\n  buffer:\n    flag: 50.0\n")
    val missing =
      apply.apply(Delta(mapOf("config.yml" to listOf(Change("ai/enabled", "true", "false")))))

    assertIs<ApplyResult.Refused>(missing)
  }
}
