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

import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelKey
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import org.spongepowered.configurate.ConfigurationNode

data class PunishNode(
  val name: String,
  val key: String,
  val models: Set<String> = emptySet(),
  val labels: Set<String> = emptySet(),
  val expireMillis: Long = 0L,
  val actions: NavigableMap<Int, String> = TreeMap(),
  val groups: List<PunishNode> = emptyList(),
  val other: PunishNode? = null,
  val swallows: Boolean = false,
) {
  val specificity: Int = (if (labels.isEmpty()) 0 else 2) + (if (models.isEmpty()) 0 else 1)

  fun matches(model: String?, label: String): Boolean =
    (models.isEmpty() || model in models) && (labels.isEmpty() || label in labels)

  fun actionAt(count: Int): String? = actions.floorEntry(count)?.value

  fun all(): List<PunishNode> =
    listOf(this) + groups.flatMap { it.all() } + (other?.all() ?: emptyList())
}

class PunishmentTree(
  val commands: Map<String, List<String>>,
  val root: PunishNode,
  val problems: List<String> = emptyList(),
) {
  val actions: Map<String, List<PunishAction>> = commands.mapValues { (_, list) ->
    list.mapNotNull(PunishAction::parse)
  }

  fun route(address: String, singleHead: Boolean = true): PunishNode? {
    val parsed = DetectionKey.parseAddress(address)
    return route(parsed?.model, parsed?.label ?: address, singleHead)
  }

  fun route(key: DetectionKey, singleHead: Boolean = true): PunishNode? =
    route(key.model, key.label, singleHead)

  private fun route(model: String?, raw: String, singleHead: Boolean): PunishNode? {
    val label = if (singleHead || raw.trim() != LabelKey.UNATTRIBUTED) labelOf(raw) else raw.trim()
    return descend(root, model?.let { LabelKey.canonical(it) ?: it }, label)
  }

  private fun descend(node: PunishNode, model: String?, label: String): PunishNode? {
    val pick =
      node.groups.filter { it.matches(model, label) }.maxByOrNull { it.specificity }
        ?: node.other?.takeIf { it.matches(model, label) }
    return when {
      pick == null -> node.takeIf { it !== root && it.swallows }
      pick.groups.isEmpty() && pick.other == null -> pick
      else -> descend(pick, model, label)
    }
  }

  companion object {
    const val GENERAL = "general"
    val EMPTY = PunishmentTree(emptyMap(), PunishNode("", ""))

    fun labelOf(raw: String): String =
      if (raw.trim() == LabelKey.UNATTRIBUTED) GENERAL else LabelKey.canonical(raw) ?: raw
  }
}

object PunishmentTreeParser {
  private const val VERSION = 2

  fun isCurrent(root: ConfigurationNode): Boolean = root.node("config-version").getInt(0) >= VERSION

  fun parse(root: ConfigurationNode): PunishmentTree {
    val problems = mutableListOf<String>()
    val commands =
      root.node("commands").childrenMap().entries.associate { (name, value) ->
        name.toString() to strings(value)
      }
    val top =
      PunishNode(
        name = "",
        key = "",
        groups = groups(root.node("groups"), "", 0L, problems),
        other = other(root.node("other"), "", 0L, problems),
      )
    for (node in top.all()) {
      for (set in node.actions.values) {
        if (set !in commands) problems += "group ${node.key} uses unknown command set '$set'"
      }
      duplicateClaims(node).forEach { problems += it }
    }
    return PunishmentTree(commands, top, problems)
  }

  private fun groups(
    node: ConfigurationNode,
    parent: String,
    expire: Long,
    problems: MutableList<String>,
  ): List<PunishNode> =
    node.childrenMap().entries.map { (name, value) ->
      group(name.toString(), value, parent, expire, problems)
    }

  private fun other(
    node: ConfigurationNode,
    parent: String,
    expire: Long,
    problems: MutableList<String>,
  ): PunishNode? = if (node.virtual()) null else group(OTHER, node, parent, expire, problems)

  private fun group(
    name: String,
    node: ConfigurationNode,
    parent: String,
    inherited: Long,
    problems: MutableList<String>,
  ): PunishNode {
    val key = if (parent.isEmpty()) name else "$parent/$name"
    for (field in node.childrenMap().keys.map { it.toString() }) {
      if (field !in GROUP_FIELDS) problems += "group $key has unknown field '$field'"
    }
    val leaf = node.node("groups").virtual() && node.node(OTHER).virtual()
    if (leaf && node.node("actions").virtual()) {
      problems += "group $key has no actions, its flags are counted but never punished"
    }
    val expire = node.node("expire").string?.let { durationMillis(it) ?: badExpire(key, problems) }
    val own = expire ?: inherited
    val actions = TreeMap<Int, String>()
    for ((step, value) in node.node("actions").childrenMap()) {
      val number = step.toString().toIntOrNull()
      val set = value.string
      if (number == null || number < 1 || set.isNullOrBlank()) {
        problems += "group $key has a bad step '$step'"
      } else {
        actions[number] = set
      }
    }
    return PunishNode(
      name = name,
      key = key,
      models = strings(node.node("models")).mapNotNull(LabelKey::canonical).toSet(),
      labels = strings(node.node("labels")).map(PunishmentTree::labelOf).toSet(),
      expireMillis = own,
      actions = actions,
      groups = groups(node.node("groups"), key, own, problems),
      other = other(node.node("other"), key, own, problems),
      swallows = !node.node("actions").virtual(),
    )
  }

  private fun duplicateClaims(node: PunishNode): List<String> =
    node.groups
      .filter { it.labels.isNotEmpty() }
      .flatMap { group -> group.labels.map { (it to group.models) to group.key } }
      .groupBy({ it.first }, { it.second })
      .filterValues { it.size > 1 }
      .map { (claim, keys) ->
        "label '${claim.first}' is named by ${keys.joinToString()}, ${keys.first()} wins"
      }

  private fun badExpire(key: String, problems: MutableList<String>): Long? {
    problems += "group $key has a bad expire"
    return null
  }

  fun durationMillis(raw: String): Long? {
    val match = DURATION.matchEntire(raw.trim().lowercase(Locale.ROOT)) ?: return null
    val amount = match.groupValues[1].toLong()
    val unit =
      when (match.groupValues[2]) {
        "s" -> SECOND
        "m" -> SECOND * SECONDS_PER_MINUTE
        "h" -> SECOND * SECONDS_PER_HOUR
        "d" -> SECOND * SECONDS_PER_DAY
        else -> SECOND * SECONDS_PER_DAY * DAYS_PER_WEEK
      }
    return amount * unit
  }

  fun strings(node: ConfigurationNode): List<String> =
    if (node.isList) node.childrenList().mapNotNull { it.string } else listOfNotNull(node.string)

  const val OTHER = "other"
  private val GROUP_FIELDS = setOf("models", "labels", "expire", "actions", "groups", OTHER)
  private val DURATION = Regex("""(\d{1,6})([smhdw])""")
  private const val SECOND = 1000L
  private const val SECONDS_PER_MINUTE = 60L
  private const val SECONDS_PER_HOUR = 3600L
  private const val SECONDS_PER_DAY = 86_400L
  private const val DAYS_PER_WEEK = 7L
}
