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

import tools.jackson.databind.JsonNode

object PunishmentsFile {
  fun withTree(text: String, doc: JsonNode): String {
    val (kept, insertAt) = withoutTree(text.lines())
    val before = kept.subList(0, insertAt).dropLastWhile { it.isBlank() }
    val after =
      kept.subList(insertAt, kept.size).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    val out = buildList {
      addAll(before)
      if (before.isNotEmpty()) add("")
      addAll(PunishmentDoc.yaml(doc).trimEnd().lines())
      if (after.isNotEmpty()) add("")
      addAll(after)
    }
    return out.joinToString("\n") + "\n"
  }

  private fun withoutTree(lines: List<String>): Pair<List<String>, Int> {
    val kept = mutableListOf<String>()
    var insertAt: Int? = null
    var skipping = false
    val pending = mutableListOf<String>()
    for (line in lines) {
      if (skipping && (line.startsWith('#') || line.isBlank())) {
        pending += line
        continue
      }
      if (line.isNotEmpty() && !line[0].isWhitespace()) {
        val tree = isTreeKey(line)
        if (skipping && !tree) kept += pending
        skipping = tree
        if (tree) insertAt = insertAt ?: kept.size
      }
      pending.clear()
      if (!skipping) kept += line
    }
    return kept to (insertAt ?: kept.size)
  }

  private fun isTreeKey(line: String): Boolean =
    TOP_KEY.find(line)?.groupValues?.get(1).let {
      it == PunishmentDoc.GROUPS || it == PunishmentDoc.OTHER
    }

  private val TOP_KEY = Regex("""^["']?([^"':#]+)["']?\s*:""")
}
