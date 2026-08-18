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

import ac.shard.config.ConfigMigrations
import ac.shard.config.MitigationsFile
import ac.shard.mitigation.MitigationTier
import ac.shard.mitigation.RuleEffects
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class RulesBlockTest {

  private fun shippedText(): String =
    this::class
      .java
      .classLoader
      .getResourceAsStream("mitigations.yml")
      ?.bufferedReader()
      ?.readText() ?: error("bundled mitigations.yml is missing from the test classpath")

  private fun parse(text: String) =
    MitigationsFile.read(
      YamlConfigurationLoader.builder().source { text.reader().buffered() }.build().load(),
      mutableListOf(),
    )

  private fun plain(text: String, section: String): Any? {
    val node = YamlConfigurationLoader.builder().source { text.reader().buffered() }.build().load()
    fun walk(n: org.spongepowered.configurate.ConfigurationNode): Any? =
      when {
        n.isMap -> n.childrenMap().entries.associate { it.key.toString() to walk(it.value) }
        n.isList -> n.childrenList().map { walk(it) }
        else -> n.rawScalar()
      }
    return walk(node.node(section))
  }

  @Test
  fun `the shipped rules survive a render and a read back`() {
    val text = shippedText()
    val rules = plain(text, "rules")

    val rewritten = RulesBlock.replace(text, "rules", rules)
    assertNotNull(rewritten)

    val before = parse(text)
    val after = parse(rewritten)

    assertEquals(before.rules.map { it.id }, after.rules.map { it.id })
    assertEquals(before.rules.map { it.effects }, after.rules.map { it.effects })
    assertEquals(before.rules.map { it.entry }, after.rules.map { it.entry })
    assertEquals(before.rules.map { it.until }, after.rules.map { it.until })
    assertEquals(before.rules.map { it.timing }, after.rules.map { it.timing })
    assertEquals(before.probabilityThresholds, after.probabilityThresholds)
  }

  @Test
  fun `everything outside the block is left alone, comments included`() {
    val text = shippedText() + "\n# a comment below the block\n"
    val rewritten = RulesBlock.replace(text, "rules", plain(text, "rules"))!!

    assertContains(rewritten, "# Shard mitigations")
    assertContains(rewritten, "config-version: ${ConfigMigrations.MITIGATIONS_LATEST_VERSION}")
    assertContains(rewritten, "half-life-minutes: 20")
    assertContains(rewritten, "# a comment below the block")
    assertEquals(
      text.substringBefore("rules:"),
      rewritten.substringBefore("rules:"),
      "not one byte above the block may move",
    )
  }

  @Test
  fun `a replaced block reads back as the rules that were written`() {
    val text = shippedText()
    val replacement =
      listOf(
        mapOf(
          "id" to "only",
          "level" to "high",
          "when" to mapOf("score" to mapOf("above" to 12.0)),
          "then" to mapOf("melee" to 0.5),
          "timing" to mapOf("delay-seconds" to listOf(1, 2), "starts-in-combat" to true),
        )
      )

    val rewritten = RulesBlock.replace(text, "rules", replacement)!!
    val settings = parse(rewritten)

    assertEquals(listOf("only"), settings.rules.map { it.id })
    assertEquals(MitigationTier.HIGH, settings.rules.first().level)
    assertEquals(RuleEffects.Flat(mapOf("melee" to 0.5)), settings.rules.first().effects)
    assertEquals(
      1_000L to 2_000L,
      with(settings.rules.first().timing) {
        delayMinMillis to delayMaxMillis
      },
    )
    assertTrue(settings.rules.first().timing.startsInCombat)
  }

  @Test
  fun `an empty list clears the block without breaking the file`() {
    val text = shippedText() + "\n# a comment below the block\n"
    val rewritten = RulesBlock.replace(text, "rules", emptyList<Any>())!!

    assertEquals(emptyList(), parse(rewritten).rules)
    assertContains(rewritten, "# a comment below the block")
  }

  @Test
  fun `a missing block is reported rather than invented`() {
    assertNull(RulesBlock.replace("enabled: true\n", "rules", emptyList<Any>()))
  }
}
