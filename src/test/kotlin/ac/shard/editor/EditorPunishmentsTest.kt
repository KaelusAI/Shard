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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class EditorPunishmentsTest {

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

  private fun actionsIn(dir: Path, group: String, level: String): List<String> {
    val root = YamlConfigurationLoader.builder().path(dir.resolve("punishments.yml")).build().load()
    return root
      .node("Punishments", group, "actions")
      .childrenMap()
      .entries
      .firstOrNull { it.key.toString() == level }
      ?.value
      ?.getList(String::class.java)
      .orEmpty()
  }

  @Test
  fun `a punishment group the operator asked for is written`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          punishments =
            listOf(
              PunishmentEdit(
                "AI",
                mapOf(
                  "5" to listOf("[alert]", "[log]"),
                  "10" to listOf("[alert]", "kick <player> please stop"),
                  "20" to listOf("[alert]", "ban <player> Shard found your aim unusual", "[reset]"),
                ),
              )
            )
        )
      )

    assertIs<ApplyResult.Applied>(result)
    assertEquals(listOf("[alert]", "[log]"), actionsIn(dir, "AI", "5"))
    assertEquals(
      listOf("[alert]", "ban <player> Shard found your aim unusual", "[reset]"),
      actionsIn(dir, "AI", "20"),
    )
  }

  @Test
  fun `the check names the group binds are left alone`(@TempDir dir: Path) {
    val apply = world(dir)

    apply.apply(Delta(punishments = listOf(PunishmentEdit("AI", mapOf("1" to listOf("[alert]"))))))

    val root = YamlConfigurationLoader.builder().path(dir.resolve("punishments.yml")).build().load()
    assertEquals(
      listOf("AI"),
      root.node("Punishments", "AI", "checks").getList(String::class.java),
      "the editor may change the actions, not what the group watches",
    )
  }

  @Test
  fun `a message that hides a command inside itself never lands in the file`(@TempDir dir: Path) {
    val before = bundled("punishments.yml")
    val apply = world(dir)

    listOf(
        "[broadcast]op <player>",
        "[broadcast] <click:run_command:'/op x'>free stuff",
        "[wait] soon",
        "kick Notch please stop",
      )
      .forEach { hostile ->
        val result =
          apply.apply(
            Delta(
              punishments = listOf(PunishmentEdit("AI", mapOf("1" to listOf("[alert]", hostile))))
            )
          )
        assertIs<ApplyResult.Refused>(result, "should have refused: $hostile")
      }

    assertEquals(before, dir.resolve("punishments.yml").toFile().readText())
  }

  @Test
  fun `an arbitrary console command lands only behind a confirmation`(@TempDir dir: Path) {
    val apply = world(dir)
    val delta =
      Delta(
        punishments = listOf(PunishmentEdit("AI", mapOf("1" to listOf("[alert]", "op <player>"))))
      )

    assertTrue(
      EditorDiff.needsConfirming(EditorDiff.rows(delta)),
      "a command Shard cannot read must be shown to someone before it runs",
    )
    assertIs<ApplyResult.Applied>(apply.apply(delta))
    assertContains(dir.resolve("punishments.yml").toFile().readText(), "op <player>")
  }

  @Test
  fun `a group that is not in the file is reported`(@TempDir dir: Path) {
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(punishments = listOf(PunishmentEdit("Nonexistent", mapOf("1" to listOf("[alert]")))))
      )

    assertIs<ApplyResult.Refused>(result)
    assertTrue(result.reasons.single().contains("Nonexistent"))
  }

  @Test
  fun `a step that is not a violation level is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    listOf("zero", "0", "-3", "1.5").forEach { level ->
      val result =
        apply.apply(
          Delta(punishments = listOf(PunishmentEdit("AI", mapOf(level to listOf("[alert]")))))
        )
      assertIs<ApplyResult.Refused>(result, "step $level should have been refused")
    }
  }

  @Test
  fun `an absurd punishment group is refused`(@TempDir dir: Path) {
    val apply = world(dir)

    val manySteps = (1..40).associate { "$it" to listOf("[alert]") }
    assertIs<ApplyResult.Refused>(
      apply.apply(Delta(punishments = listOf(PunishmentEdit("AI", manySteps))))
    )

    val manyActions = mapOf("1" to (1..20).map { "[alert]" })
    assertIs<ApplyResult.Refused>(
      apply.apply(Delta(punishments = listOf(PunishmentEdit("AI", manyActions))))
    )
  }

  @Test
  fun `punishments travel in the same transaction as the config`(@TempDir dir: Path) {
    val beforeConfig = bundled("config.yml")
    val apply = world(dir)

    val result =
      apply.apply(
        Delta(
          changes = mapOf("config.yml" to listOf(Change("ai/buffer/flag", "50.0", "80.0"))),
          punishments = listOf(PunishmentEdit("AI", mapOf("1" to listOf("[wait] soon")))),
        )
      )

    assertIs<ApplyResult.Refused>(result)
    assertEquals(
      beforeConfig,
      dir.resolve("config.yml").toFile().readText(),
      "a refused punishment must take the config change down with it",
    )
  }

  @Test
  fun `the file still reads after the group is rewritten`(@TempDir dir: Path) {
    val apply = world(dir)

    apply.apply(
      Delta(
        punishments =
          listOf(
            PunishmentEdit(
              "AI",
              mapOf(
                "1" to listOf("[broadcast] <#39FF14>caught <player>", "tempban <player> 20h no")
              ),
            )
          )
      )
    )

    val text = dir.resolve("punishments.yml").toFile().readText()
    assertContains(text, "Punishments:")
    assertContains(text, "- \"[broadcast] <#39FF14>caught <player>\"")
  }
}
