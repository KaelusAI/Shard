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
package ac.shard.punishment

import ac.shard.database.InMemoryViolationDatabase
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class PunishmentTreeTest {
  private fun load(yaml: String) =
    YamlConfigurationLoader.builder()
      .source { yaml.trimIndent().reader().buffered() }
      .build()
      .load()

  private val tree =
    PunishmentTreeParser.parse(
      load(
        """
        config-version: 2
        commands:
          alert: ["[alert]"]
          warn: ["kick <player> x"]
          ban: ["tempban <player> 1d x"]
        groups:
          AI:
            expire: 30d
            groups:
              General:
                labels: ["general"]
                actions: { 1: warn, 3: ban }
              Trigger:
                labels: ["trigger"]
                actions: {}
              Side:
                models: ["b"]
                actions: { 1: alert }
              SideAim:
                models: ["b"]
                labels: ["aim"]
                actions: { 2: ban }
            other:
              actions: { 1: alert }
        """
      )
    )

  private fun route(address: String) = tree.route(address)?.key

  @Test
  fun `a model without labels lands in general`() {
    assertEquals("AI/General", route("a/_unattributed"))
  }

  @Test
  fun `the most precise group wins wherever it stands`() {
    assertEquals("AI/SideAim", route("b/aim"), "model and label beat model alone")
    assertEquals("AI/Side", route("b/x"), "a named model beats a group that names nothing")
    assertEquals("AI/Trigger", route("b/trigger"), "a named label beats a named model")
    assertEquals("AI/Trigger", route("a/trigger"))
  }

  @Test
  fun `an undecided verdict of a model with labels is not treated as general`() {
    assertEquals("AI/other", tree.route("a/_unattributed", singleHead = false)?.key)
  }

  @Test
  fun `the migration skips groups that punish nothing or take another check`() {
    val old =
      load(
        """
        Punishments:
          Disabled:
            checks: ["AI"]
            actions: {}
          Reach:
            checks: ["Reach"]
            actions: { 1: "kick <player>" }
          AI:
            checks: "AI"
            actions:
              1: "tempban <player> 1d x"
        """
      )
    val converted = PunishmentTreeParser.parse(load(PunishmentsMigration.convert(old)))
    assertEquals(listOf("AI"), converted.root.groups.map { it.name })
    assertEquals(listOf("tempban <player> 1d x"), converted.commands["ai-1"])
    assertEquals("AI", converted.route("base/_unattributed")?.key)
  }

  @Test
  fun `a label nobody named goes to other`() {
    assertEquals("AI/other", route("a/speed"))
  }

  @Test
  fun `a flag no group takes is not punished`() {
    val narrow =
      PunishmentTreeParser.parse(
        load("config-version: 2\ncommands: {}\ngroups:\n  aim: { labels: [\"aim\"], actions: {} }")
      )
    assertNull(narrow.route("a/speed"))
  }

  @Test
  fun `expire is inherited and steps map to command sets`() {
    val general = tree.root.all().first { it.key == "AI/General" }
    assertEquals(30L * 86_400_000L, general.expireMillis)
    assertEquals("ban", general.actions[3])
    assertTrue(tree.problems.isEmpty(), tree.problems.toString())
  }

  @Test
  fun `an unknown command set and a doubly named label are reported`() {
    val broken =
      PunishmentTreeParser.parse(
        load(
          """
          config-version: 2
          commands: {}
          groups:
            A: { labels: ["aim"], actions: { 1: nope } }
            B: { labels: ["aim"], actions: {} }
          """
        )
      )
    assertTrue(broken.problems.any { "unknown command set 'nope'" in it })
    assertTrue(broken.problems.any { "label 'aim' is named by A, B" in it })
  }

  @Test
  fun `the shipped file parses clean and punishes a model without labels`() {
    val shipped =
      File(
        this::class.java.classLoader.getResource("punishments.yml")?.toURI()
          ?: error("bundled punishments.yml is missing")
      )
    val parsed =
      PunishmentTreeParser.parse(
        YamlConfigurationLoader.builder().path(shipped.toPath()).build().load()
      )
    assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
    val general = parsed.route("base/_unattributed")
    assertEquals("general", general?.key)
    assertEquals(30L * 86_400_000L, general?.expireMillis)
    assertEquals("general", parsed.route("base/aim")?.key)
    val other = parsed.route("base/x")
    assertEquals("other", other?.key)
    assertEquals(30L * 86_400_000L, other?.expireMillis)
  }

  @Test
  fun `an old file converts to the same groups and steps`() {
    val old =
      load(
        """
        Punishments:
          AI:
            checks: ["AI"]
            actions:
              1: ["[alert]", "kick <player> \"hi\""]
              3: ["tempban <player> 1h x"]
        """
      )
    assertTrue(PunishmentsMigration.isLegacy(old))
    val converted = PunishmentTreeParser.parse(load(PunishmentsMigration.convert(old)))
    assertTrue(converted.problems.isEmpty(), converted.problems.toString())
    assertEquals(listOf("[alert]", "kick <player> \"hi\""), converted.commands["ai-1"])
    val group = converted.root.groups.single()
    assertEquals("AI", converted.route("base/_unattributed")?.key)
    assertEquals(
      listOf(null, "ai-1", "ai-1", "ai-3", "ai-3", "ai-3"),
      (0..5).map { group.actionAt(it) },
      "every flag runs the step at or below its count, as 2.0.0 did",
    )
  }

  @Test
  fun `groups whose names clean up the same way get their own command sets`() {
    val old =
      load(
        """
        Punishments:
          Бан: { checks: ["AI"], labels: ["aim"], actions: { 1: "ban <player>" } }
          Кик: { checks: ["AI"], labels: ["trigger"], actions: { 1: "kick <player>" } }
        """
      )
    val converted = PunishmentTreeParser.parse(load(PunishmentsMigration.convert(old)))

    assertEquals(listOf("ban <player>"), converted.commands[converted.route("m/aim")?.actionAt(1)])
    assertEquals(
      listOf("kick <player>"),
      converted.commands[converted.route("m/trigger")?.actionAt(1)],
    )
    assertEquals(2, PunishmentsMigration.groupCount(old))
  }

  @Test
  fun `a group that can never punish is reported`() {
    val parsed =
      PunishmentTreeParser.parse(
        load(
          "config-version: 2\ncommands: {}\ngroups:\n  aim: { labels: [\"aim\"], action: { 1: x } }"
        )
      )
    assertTrue(parsed.problems.any { "unknown field 'action'" in it }, parsed.problems.toString())
    assertTrue(parsed.problems.any { "aim has no actions" in it }, parsed.problems.toString())
  }

  @Test
  fun `only flags inside the window count`() {
    val db = InMemoryViolationDatabase()
    val player = UUID.randomUUID()
    db.recordFlag(player, "AI/General", 1_000L, 0L)
    db.recordFlag(player, "AI/General", 2_000L, 0L)
    assertEquals(2, db.recordFlag(player, "AI/General", 50_000L, 1_500L))
  }
}
