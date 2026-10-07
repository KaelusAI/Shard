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

import ac.shard.punishment.PunishmentTreeParser
import java.security.MessageDigest
import org.spongepowered.configurate.ConfigurationNode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

@Suppress("TooManyFunctions")
object PunishmentDoc {
  private val factory = JsonNodeFactory.instance

  fun empty(): ObjectNode = factory.objectNode()

  fun of(root: ConfigurationNode): ObjectNode {
    val doc = factory.objectNode()
    groupsOf(root.node(GROUPS))?.let { doc.set(GROUPS, it) }
    if (!root.node(OTHER).virtual()) doc.set(OTHER, nodeOf(root.node(OTHER)))
    return doc
  }

  fun commandNames(root: ConfigurationNode): List<String> =
    root.node("commands").childrenMap().keys.map { it.toString() }.sorted()

  private fun groupsOf(node: ConfigurationNode): ObjectNode? {
    if (node.virtual() || !node.isMap) return null
    val groups = factory.objectNode()
    for ((name, child) in node.childrenMap()) groups.set(name.toString(), nodeOf(child))
    return groups
  }

  private fun nodeOf(node: ConfigurationNode): ObjectNode {
    val out = factory.objectNode()
    for (key in listOf(MODELS, LABELS)) {
      val values = PunishmentTreeParser.strings(node.node(key))
      if (!node.node(key).virtual()) {
        out.set(key, factory.arrayNode().apply { values.forEach(::add) })
      }
    }
    node.node(EXPIRE).string?.let { out.put(EXPIRE, it) }
    if (!node.node(ACTIONS).virtual()) {
      val actions = factory.objectNode()
      for ((step, value) in node.node(ACTIONS).childrenMap()) {
        actions.put(step.toString(), value.string.orEmpty())
      }
      out.set(ACTIONS, actions)
    }
    groupsOf(node.node(GROUPS))?.let { out.set(GROUPS, it) }
    if (!node.node(OTHER).virtual()) out.set(OTHER, nodeOf(node.node(OTHER)))
    return out
  }

  fun normalize(doc: JsonNode): ObjectNode {
    val out = factory.objectNode()
    doc.get(GROUPS)?.let { out.set(GROUPS, normalizeGroups(it)) }
    doc.get(OTHER)?.let { out.set(OTHER, normalizeNode(it)) }
    return out
  }

  private fun normalizeGroups(groups: JsonNode): ObjectNode {
    val out = factory.objectNode()
    groups.properties().forEach { (name, child) -> out.set(name, normalizeNode(child)) }
    return out
  }

  private fun normalizeNode(node: JsonNode): ObjectNode {
    val out = factory.objectNode()
    for (key in listOf(MODELS, LABELS, EXPIRE)) node.get(key)?.let {
      out.set(key, it.deepCopy())
    }
    node.get(ACTIONS)?.let { actions ->
      val sorted = factory.objectNode()
      actions
        .properties()
        .asSequence()
        .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
        .forEach { (step, set) -> sorted.set(step, set.deepCopy()) }
      out.set(ACTIONS, sorted)
    }
    node.get(GROUPS)?.let { out.set(GROUPS, normalizeGroups(it)) }
    node.get(OTHER)?.let { out.set(OTHER, normalizeNode(it)) }
    return out
  }

  fun canonical(doc: JsonNode): String = normalize(doc).toString()

  fun hash(doc: JsonNode): String =
    MessageDigest.getInstance("SHA-256")
      .digest(canonical(doc).toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  fun problems(doc: JsonNode): List<String> {
    val problems = mutableListOf<String>()
    if (!doc.isObject) return listOf("tree is not an object")
    if (doc.toString().length > MAX_BYTES) problems += "tree is larger than $MAX_BYTES bytes"
    unknownKeys(doc, setOf(GROUPS, OTHER), "tree", problems)
    groupNames(doc, "tree", problems)
    var nodes = 0
    fun visit(node: JsonNode, path: String, depth: Int) {
      nodes++
      if (depth > MAX_DEPTH) problems += "$path is nested deeper than $MAX_DEPTH"
      checkNode(node, path, problems)
      node.path(GROUPS).properties().forEach { (name, child) ->
        visit(child, "$path/$name", depth + 1)
      }
      node.get(OTHER)?.let { visit(it, "$path/$OTHER", depth + 1) }
    }
    doc.path(GROUPS).properties().forEach { (name, child) -> visit(child, name, 1) }
    doc.get(OTHER)?.let { visit(it, OTHER, 1) }
    if (nodes > MAX_NODES) problems += "tree has more than $MAX_NODES groups"
    return problems
  }

  private fun checkNode(node: JsonNode, path: String, problems: MutableList<String>) {
    if (!node.isObject) {
      problems += "$path is not a group"
      return
    }
    unknownKeys(node, NODE_KEYS, path, problems)
    for (key in listOf(MODELS, LABELS)) {
      if (node.get(key)?.let(::goodList) == false) problems += "$path has bad $key"
    }
    if (node.get(EXPIRE)?.let(::goodExpire) == false) problems += "$path has a bad expire"
    if (node.get(ACTIONS)?.let(::goodActions) == false) problems += "$path has bad actions"
    groupNames(node, path, problems)
  }

  private fun groupNames(node: JsonNode, path: String, problems: MutableList<String>) {
    val groups = node.get(GROUPS) ?: return
    if (!groups.isObject) problems += "$path has bad groups"
    groups.propertyNames().forEach { name ->
      if (!NAME.matches(name) || name == OTHER) problems += "$path has a bad group name '$name'"
    }
  }

  private fun text(node: JsonNode, pattern: Regex): Boolean =
    node.isTextual && pattern.matches(node.asString(""))

  private fun goodList(list: JsonNode): Boolean =
    list.isArray && list.size() <= MAX_LIST && list.all { text(it, FILTER) }

  private fun goodExpire(expire: JsonNode): Boolean = text(expire, DURATION)

  private fun goodActions(actions: JsonNode): Boolean =
    actions.isObject &&
      actions.size() <= MAX_STEPS &&
      actions.properties().asSequence().all { (step, set) ->
        step.toIntOrNull()?.let { it in 1..MAX_STEP && it.toString() == step } == true &&
          text(set, NAME)
      }

  private fun unknownKeys(
    node: JsonNode,
    allowed: Set<String>,
    path: String,
    problems: MutableList<String>,
  ) {
    node.propertyNames().forEach { if (it !in allowed) problems += "$path has unknown field '$it'" }
  }

  fun yaml(doc: JsonNode): String = buildString {
    val groups = doc.get(GROUPS)
    if (groups != null) {
      append(GROUPS).append(":")
      if (groups.size() == 0) append(" {}")
      append('\n')
      groups.properties().forEach { (name, child) -> writeNode(this, name, child, 1) }
    }
    doc.get(OTHER)?.let {
      if (groups != null) append('\n')
      writeNode(this, OTHER, it, 0)
    }
  }

  private fun writeNode(out: StringBuilder, name: String, node: JsonNode, level: Int) {
    val indent = "  ".repeat(level)
    out.append(indent).append(key(name)).append(':')
    if (node.size() == 0) {
      out.append(" {}\n")
      return
    }
    out.append('\n')
    val inner = "  ".repeat(level + 1)
    for (field in listOf(MODELS, LABELS)) {
      node.get(field)?.let { list ->
        out.append(inner).append(field).append(": [")
        out.append(list.joinToString(", ") { quote(it.asString("")) }).append("]\n")
      }
    }
    node.get(EXPIRE)?.let {
      out.append(inner).append(EXPIRE).append(": ").append(it.asString("")).append('\n')
    }
    node.get(ACTIONS)?.let { actions ->
      out.append(inner).append(ACTIONS).append(": ")
      val steps =
        actions
          .properties()
          .asSequence()
          .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
          .toList()
      if (steps.isEmpty()) {
        out.append("{}\n")
      } else {
        out.append("{ ")
        out.append(steps.joinToString(", ") { (step, set) -> "$step: ${key(set.asString(""))}" })
        out.append(" }\n")
      }
    }
    node.get(GROUPS)?.let { groups ->
      out.append(inner).append(GROUPS).append(":")
      if (groups.size() == 0) out.append(" {}")
      out.append('\n')
      groups.properties().forEach { (child, value) -> writeNode(out, child, value, level + 2) }
    }
    node.get(OTHER)?.let { writeNode(out, OTHER, it, level + 1) }
  }

  private fun key(value: String): String =
    if (BARE.matches(value) && value.lowercase() !in YAML_WORDS) value else quote(value)

  private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  const val GROUPS = "groups"
  const val OTHER = "other"
  private const val MODELS = "models"
  private const val LABELS = "labels"
  private const val EXPIRE = "expire"
  private const val ACTIONS = "actions"
  private val NODE_KEYS = setOf(MODELS, LABELS, EXPIRE, ACTIONS, GROUPS, OTHER)
  private val NAME = Regex("[A-Za-z0-9_.-]{1,64}")
  private val DURATION = Regex("\\d{1,6}[smhdw]")
  private val FILTER = Regex("[A-Za-z0-9_.:/-]{1,64}")
  private val BARE = Regex("[A-Za-z][A-Za-z0-9_.-]*")
  private val YAML_WORDS = setOf("true", "false", "yes", "no", "on", "off", "null", "y", "n")
  private const val MAX_BYTES = 65_536
  private const val MAX_DEPTH = 6
  private const val MAX_NODES = 128
  private const val MAX_LIST = 64
  private const val MAX_STEPS = 64
  private const val MAX_STEP = 100_000
}
