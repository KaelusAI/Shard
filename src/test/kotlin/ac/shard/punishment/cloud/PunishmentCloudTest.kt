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
package ac.shard.punishment.cloud

import ac.shard.http.Json
import ac.shard.punishment.PunishmentTreeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import tools.jackson.databind.node.ObjectNode

class PunishmentCloudTest {
  private val mapper = Json.mapper

  private fun load(text: String) =
    YamlConfigurationLoader.builder().source { text.reader().buffered() }.build().load()

  private fun json(text: String) = mapper.readTree(text) as ObjectNode

  private val shipped =
    this::class.java.classLoader.getResource("punishments.yml")?.readText()
      ?: error("bundled punishments.yml is missing")

  @Test
  fun `the shipped tree survives a trip into the file and back`() {
    val doc = PunishmentDoc.of(load(shipped))
    val rewritten = PunishmentsFile.withTree(shipped, doc)

    assertEquals(doc, PunishmentDoc.of(load(rewritten)))
    assertTrue(PunishmentDoc.problems(doc).isEmpty(), PunishmentDoc.problems(doc).toString())
    assertTrue(rewritten.startsWith(shipped.substringBefore("groups:")), "header and commands stay")
  }

  @Test
  fun `a cloud tree replaces only the tree and keeps commands and comments`() {
    val file =
      """
      # top
      config-version: 2
      commands:
        warn: ["kick <player>"]
      groups:
        general:
          labels: ["general"]
          actions: { 1: warn }
      # between
      other:
        actions: {}
      """
        .trimIndent() + "\n"
    val cloud =
      json(
        """{"groups":{"on":{"models":["b"],"expire":"7d","actions":{"2":"warn"},
          "groups":{"aim":{"labels":["aim"]}}}},"other":{"actions":{"1":"warn"}}}"""
      )

    val out = PunishmentsFile.withTree(file, cloud)
    val root = load(out)

    assertEquals(cloud, PunishmentDoc.of(root))
    assertTrue(out.startsWith("# top\nconfig-version: 2\ncommands:\n  warn:"), out)
    assertTrue("\"on\":" in out, "a YAML word used as a name is quoted")
    val tree = PunishmentTreeParser.parse(root)
    assertEquals("on/aim", tree.route("b/aim")?.key)
    assertEquals(7L * 86_400_000L, tree.route("b/aim")?.expireMillis)
  }

  @Test
  fun `a comment above the next section survives a rewrite`() {
    val file = "config-version: 2\ngroups:\n  a: {}\n\n# command sets\ncommands:\n  x: []\n"

    val out = PunishmentsFile.withTree(file, json("""{"groups":{"b":{}}}"""))

    assertTrue("# command sets\ncommands:" in out, out)
    assertTrue("  b: {}" in out && "  a: {}" !in out, out)
  }

  @Test
  fun `each side keeps what only it changed`() {
    val base = json("""{"groups":{"g":{"actions":{"1":"warn"},"expire":"30d"}}}""")
    val local = json("""{"groups":{"g":{"actions":{"1":"warn"},"expire":"7d"}}}""")
    val cloud = json("""{"groups":{"g":{"actions":{"2":"ban"},"expire":"30d"},"n":{}}}""")

    val result = TreeMerge.merge(base, local, cloud)

    assertEquals(
      json("""{"groups":{"g":{"actions":{"2":"ban"},"expire":"7d"},"n":{}}}"""),
      result.tree,
    )
    assertTrue(result.conflicts.isEmpty())
  }

  @Test
  fun `a field changed on both sides keeps the local value`() {
    val base = json("""{"groups":{"g":{"expire":"30d"}}}""")
    val local = json("""{"groups":{"g":{"expire":"7d"}}}""")
    val cloud = json("""{"groups":{"g":{"expire":"1d"}}}""")

    val result = TreeMerge.merge(base, local, cloud)

    assertEquals(local, result.tree)
    assertEquals(listOf("groups/g/expire"), result.conflicts)
  }

  @Test
  fun `a group edited on one side and removed on the other is kept`() {
    val base = json("""{"groups":{"g":{"expire":"30d"}}}""")
    val edited = json("""{"groups":{"g":{"expire":"7d"}}}""")
    val removed = json("""{"groups":{}}""")

    assertEquals(edited, TreeMerge.merge(base, edited, removed).tree)
    assertEquals(edited, TreeMerge.merge(base, removed, edited).tree)
  }

  @Test
  fun `a group removed in the panel goes away when nobody touched it here`() {
    val base = json("""{"groups":{"g":{"expire":"30d"},"h":{}}}""")
    val cloud = json("""{"groups":{"h":{}}}""")

    assertEquals(cloud, TreeMerge.merge(base, base, cloud).tree)
  }

  @Test
  fun `a cloud tree that could run anything or break the parser is refused`() {
    val problems =
      PunishmentDoc.problems(
        json(
          """{"commands":{"x":["op <player>"]},"groups":{"bad name!":{},
            "g":{"actions":{"0":"warn","2":"a b"},"expire":"soon","labels":"aim","run":1}}}"""
        )
      )

    assertTrue(problems.any { "unknown field 'commands'" in it }, problems.toString())
    assertTrue(problems.any { "bad group name 'bad name!'" in it }, problems.toString())
    assertTrue(problems.any { "g has bad actions" in it }, problems.toString())
    assertTrue(problems.any { "g has a bad expire" in it }, problems.toString())
    assertTrue(problems.any { "g has bad labels" in it }, problems.toString())
    assertTrue(problems.any { "unknown field 'run'" in it }, problems.toString())
  }

  @Test
  fun `groups reordered in the panel reach the file, field order inside a group does not matter`() {
    val base = json("""{"groups":{"a":{"labels":["x"]},"b":{}}}""")
    val cloud = json("""{"groups":{"b":{},"a":{"labels":["x"]}}}""")

    val merged = TreeMerge.merge(base, base, cloud).tree

    assertEquals(listOf("b", "a"), merged.path("groups").propertyNames().toList())
    assertFalse(TreeMerge.same(base, merged), "a new group order is a change")
    assertTrue(
      TreeMerge.same(
        PunishmentDoc.normalize(json("""{"other":{"actions":{"3":"b","1":"a"},"expire":"1d"}}""")),
        PunishmentDoc.normalize(json("""{"other":{"expire":"1d","actions":{"1":"a","3":"b"}}}""")),
      )
    )
  }

  @Test
  fun `the hash follows group order but not field order`() {
    val a = json("""{"groups":{"a":{},"b":{"expire":"1d","labels":["x"]}}}""")
    val sameFields = json("""{"groups":{"a":{},"b":{"labels":["x"],"expire":"1d"}}}""")
    val swapped = json("""{"groups":{"b":{"labels":["x"],"expire":"1d"},"a":{}}}""")

    assertEquals(PunishmentDoc.hash(a), PunishmentDoc.hash(sameFields))
    assertFalse(PunishmentDoc.hash(a) == PunishmentDoc.hash(swapped))
  }

  @Test
  fun `values that change when written to YAML are refused`() {
    val problems =
      PunishmentDoc.problems(json("""{"groups":{"g":{"expire":" 30d","actions":{"01":"warn"}}}}"""))

    assertTrue(problems.any { "g has a bad expire" in it }, problems.toString())
    assertTrue(problems.any { "g has bad actions" in it }, problems.toString())
  }

  @Test
  fun `a merge with fields added on both sides still matches the written file`() {
    val base = json("""{"groups":{"g":{"labels":["x"],"actions":{"1":"warn"}}}}""")
    val local = json("""{"groups":{"g":{"labels":["x"],"actions":{"1":"warn","10":"ban"}}}}""")
    val cloud =
      json("""{"groups":{"g":{"labels":["x"],"expire":"7d","actions":{"1":"warn","5":"kick"}}}}""")
    val file = "config-version: 2\ncommands: {}\n"

    val merged = TreeMerge.merge(base, local, cloud).tree
    val written =
      PunishmentDoc.normalize(PunishmentDoc.of(load(PunishmentsFile.withTree(file, merged))))

    assertTrue(TreeMerge.same(merged, written), "$merged vs $written")
  }
}
