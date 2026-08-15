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
package ac.shard.config.yaml

import java.io.File
import ru.vyarus.yaml.updater.parse.comments.CommentsReader
import ru.vyarus.yaml.updater.parse.comments.CommentsWriter
import ru.vyarus.yaml.updater.parse.comments.model.CmtNode
import ru.vyarus.yaml.updater.parse.comments.model.CmtNodeFactory
import ru.vyarus.yaml.updater.parse.comments.model.CmtTree

sealed interface PatchResult {
  data object Applied : PatchResult

  data object NotFound : PatchResult

  data class Unsupported(val reason: String) : PatchResult
}

private const val INDENT = 2
private const val NO_QUOTE = ' '
private const val DOUBLE_QUOTE = '"'
private const val SINGLE_QUOTE = '\''

object YamlPatcher {

  fun read(file: File): CmtTree = CommentsReader.read(file)

  fun render(tree: CmtTree): String = CommentsWriter.write(tree)

  fun has(tree: CmtTree, path: String): Boolean = tree.find(path) != null

  fun readScalar(tree: CmtTree, path: String): String? {
    val node = tree.find(path) ?: return null
    val lines = node.value
    return if (node.children.isNotEmpty() || lines.size != 1) null else stripComment(lines.first())
  }

  fun setScalar(tree: CmtTree, path: String, value: String): PatchResult {
    val node = tree.find(path)
    return if (node == null) PatchResult.NotFound else replaceScalar(node, path, value)
  }

  fun childKeys(tree: CmtTree, path: String): List<String> =
    tree.find(path)?.children.orEmpty().mapNotNull { it.key }.map(::unquote)

  fun readStringListMap(tree: CmtTree, path: String): Map<String, List<String>>? {
    val node = tree.find(path) ?: return null
    return node.children
      .filter { it.key != null }
      .associate { world ->
        unquote(world.key) to
          world.children.filter { it.isListItem }.map { unquote(stripComment(it.value.first())) }
      }
  }

  fun setStringListMap(
    tree: CmtTree,
    parentPath: String,
    key: String,
    entries: Map<String, List<String>>,
  ): PatchResult {
    val parent = tree.find(parentPath)
    return if (parent == null) PatchResult.NotFound else rebuildSection(parent, key, entries)
  }

  private fun replaceScalar(node: CmtNode, path: String, value: String): PatchResult {
    val lines = node.value
    return when {
      node.children.isNotEmpty() -> PatchResult.Unsupported("$path holds a section, not a value")
      node.isListItem -> PatchResult.Unsupported("$path is a list item")
      lines.isEmpty() -> PatchResult.Unsupported("$path has no value to replace")
      lines.size > 1 -> PatchResult.Unsupported("$path spans several lines")
      else -> {
        node.value = listOf(rebuildLine(lines.first(), value))
        PatchResult.Applied
      }
    }
  }

  private fun rebuildSection(
    parent: CmtNode,
    key: String,
    entries: Map<String, List<String>>,
  ): PatchResult {
    val existing = parent.children.firstOrNull { it.key == key }
    val padding = parent.children.firstOrNull()?.padding ?: (parent.padding + INDENT)
    var line = (parent.children.maxOfOrNull { it.lineNum } ?: parent.lineNum) + 1
    val section = existing ?: CmtNodeFactory.createProperty(parent, padding, line, key)
    section.children.clear()
    entries.forEach { (world, names) ->
      line++
      val worldNode =
        CmtNodeFactory.createProperty(section, padding + INDENT, line, plainOrQuoted(world))
      names.forEach { name ->
        line++
        worldNode.add(
          CmtNodeFactory.createListValue(worldNode, padding + INDENT * 2, line, " " + quote(name))
        )
      }
      section.add(worldNode)
    }
    if (existing == null) {
      parent.add(section)
    }
    return PatchResult.Applied
  }
}

private val PLAIN_KEY = Regex("[A-Za-z0-9_.-]+")

private fun quote(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun plainOrQuoted(key: String): String = if (PLAIN_KEY.matches(key)) key else quote(key)

private fun unquote(value: String): String =
  when {
    value.length >= 2 && value.startsWith(DOUBLE_QUOTE) && value.endsWith(DOUBLE_QUOTE) ->
      value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    value.length >= 2 && value.startsWith(SINGLE_QUOTE) && value.endsWith(SINGLE_QUOTE) ->
      value.substring(1, value.length - 1)
    else -> value
  }

private fun stripComment(raw: String): String {
  val lead = raw.takeWhile { it == ' ' || it == '\t' }
  val body = raw.substring(lead.length)
  val comment = commentIndex(body)
  return (if (comment < 0) body else body.substring(0, comment)).trim()
}

private fun rebuildLine(raw: String, value: String): String {
  val lead = raw.takeWhile { it == ' ' || it == '\t' }
  val body = raw.substring(lead.length)
  val comment = commentIndex(body)
  val tail = if (comment < 0) "" else body.substring(comment)
  return lead.ifEmpty { " " } + value + tail
}

@Suppress("ReturnCount")
private fun commentIndex(body: String): Int {
  var quote = NO_QUOTE
  var index = 0
  while (index < body.length) {
    val char = body[index]
    when {
      quote == DOUBLE_QUOTE && char == '\\' -> index++
      quote != NO_QUOTE -> if (char == quote) quote = NO_QUOTE
      char == DOUBLE_QUOTE || char == SINGLE_QUOTE -> quote = char
      char == '#' && (index == 0 || body[index - 1].isWhitespace()) -> return backUp(body, index)
    }
    index++
  }
  return -1
}

private fun backUp(body: String, from: Int): Int {
  var start = from
  while (start > 0 && body[start - 1].isWhitespace()) {
    start--
  }
  return start
}
