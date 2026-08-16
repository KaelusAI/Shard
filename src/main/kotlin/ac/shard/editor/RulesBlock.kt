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

private const val INDENT = "  "
private val PLAIN = Regex("[A-Za-z0-9_.-]+")

object RulesBlock {

  fun replace(text: String, key: String, value: Any?): String? {
    val lines = text.lines()
    val start = lines.indexOfFirst { it.startsWith("$key:") }
    if (start < 0) return null

    val end = endOf(lines, start)
    val rendered = render(key, value)
    return (lines.take(start) + rendered + lines.drop(end)).joinToString("\n")
  }

  private fun endOf(lines: List<String>, start: Int): Int =
    (start + 1 until lines.size).firstOrNull {
      lines[it].isNotBlank() && !lines[it].first().isWhitespace()
    } ?: lines.size

  private fun render(key: String, value: Any?): List<String> {
    if (value == null) return listOf("$key:")
    val body = renderValue(value, 1)
    return if (body.isEmpty()) listOf("$key: ${inline(value)}") else listOf("$key:") + body
  }

  private fun renderValue(value: Any?, depth: Int): List<String> {
    val pad = INDENT.repeat(depth)
    return when (value) {
      is Map<*, *> ->
        value.entries.flatMap { (rawKey, child) ->
          val name = scalar(rawKey.toString())
          val nested = renderValue(child, depth + 1)
          if (nested.isEmpty()) listOf("$pad$name: ${inline(child)}")
          else listOf("$pad$name:") + nested
        }
      is List<*> ->
        value.flatMap { item ->
          val nested = renderValue(item, depth + 1)
          if (nested.isEmpty()) {
            listOf("$pad- ${inline(item)}")
          } else {
            listOf("$pad- " + nested.first().trimStart()) + nested.drop(1)
          }
        }
      else -> emptyList()
    }
  }

  private fun inline(value: Any?): String =
    when (value) {
      null -> "null"
      is Map<*, *> -> "{}"
      is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(", ", "[", "]") { inline(it) }
      is String -> scalar(value)
      is Boolean,
      is Number -> value.toString()
      else -> scalar(value.toString())
    }

  private fun scalar(value: String): String =
    if (PLAIN.matches(value)) value
    else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
