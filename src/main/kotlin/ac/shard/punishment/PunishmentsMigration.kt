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

import java.util.Locale
import java.util.TreeMap
import org.spongepowered.configurate.ConfigurationNode

object PunishmentsMigration {
  fun isLegacy(root: ConfigurationNode): Boolean =
    !PunishmentTreeParser.isCurrent(root) && !root.node(LEGACY_SECTION).virtual()

  private class Legacy(
    val name: String,
    val node: ConfigurationNode,
    val ladder: TreeMap<Int, List<String>>,
  )

  private fun legacyGroups(root: ConfigurationNode): List<Legacy> =
    root.node(LEGACY_SECTION).childrenMap().mapNotNull { (name, group) ->
      val steps =
        group.node("actions").childrenMap().entries.mapNotNull { (step, value) ->
          step
            .toString()
            .toIntOrNull()
            ?.takeIf { it >= 1 }
            ?.let { it to PunishmentTreeParser.strings(value) }
        }
      val checks =
        PunishmentTreeParser.strings(group.node("checks")).map { it.lowercase(Locale.ROOT) }
      if (steps.isEmpty() || checks.none { filter -> AI_NAMES.any { filter in it } }) null
      else Legacy(name.toString(), group, TreeMap(steps.toMap()))
    }

  fun groupCount(root: ConfigurationNode): Int = legacyGroups(root).size

  fun backupName(index: Int): String =
    if (index == 1) "punishments.v1.yml" else "punishments.v1-$index.yml"

  fun convert(root: ConfigurationNode, backup: String = backupName(1)): String {
    val commands = StringBuilder()
    val groups = StringBuilder()
    val used = mutableSetOf<String>()
    for (legacy in legacyGroups(root)) {
      val groupName = legacy.name
      val group = legacy.node
      val prefix = uniquePrefix(groupName, used)
      groups.append("  ").append(quote(groupName)).append(":\n")
      for (filter in listOf("models", "labels")) {
        val values = PunishmentTreeParser.strings(group.node(filter))
        if (values.isNotEmpty()) {
          groups.append("    ").append(filter).append(": [")
          groups.append(values.joinToString(", ", transform = ::quote)).append("]\n")
        }
      }
      val ladder = legacy.ladder
      for ((step, lines) in ladder) {
        commands.append("  ").append(quote("$prefix-$step")).append(":\n")
        lines.forEach { commands.append("    - ").append(quote(it)).append('\n') }
      }
      groups.append("    actions:\n")
      for (step in ladder.keys) {
        groups
          .append("      ")
          .append(step)
          .append(": ")
          .append(quote("$prefix-$step"))
          .append('\n')
      }
    }
    return "# Converted from the previous format. The original is kept in $backup.\n" +
      "config-version: 2\n\ncommands:\n" +
      commands +
      "\ngroups:\n" +
      groups
  }

  private fun uniquePrefix(group: String, used: MutableSet<String>): String {
    val base =
      group.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "group" }
    val prefix =
      if (base !in used) base
      else generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in used }
    used += prefix
    return prefix
  }

  private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private val AI_NAMES = setOf("ai", "ai (aim)")
  const val LEGACY_SECTION = "Punishments"
}
