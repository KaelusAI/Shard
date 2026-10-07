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
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

object TreeMerge {
  data class Result(val tree: ObjectNode, val conflicts: List<String>)

  fun merge(base: JsonNode, local: JsonNode, cloud: JsonNode): Result {
    val conflicts = mutableListOf<String>()
    val merged = merge(base, local, cloud, "", conflicts)
    return Result(
      PunishmentDoc.normalize(merged ?: JsonNodeFactory.instance.objectNode()),
      conflicts,
    )
  }

  fun same(a: JsonNode?, b: JsonNode?): Boolean = a?.toString() == b?.toString()

  private fun merge(
    base: JsonNode?,
    local: JsonNode?,
    cloud: JsonNode?,
    path: String,
    conflicts: MutableList<String>,
  ): JsonNode? =
    when {
      same(local, base) -> cloud
      same(cloud, base) || same(cloud, local) -> local
      local == null -> cloud
      cloud == null -> local
      local.isObject && cloud.isObject -> {
        val out = JsonNodeFactory.instance.objectNode()
        val keys = LinkedHashSet<String>()
        local.propertyNames().forEach(keys::add)
        cloud.propertyNames().forEach(keys::add)
        for (key in keys) {
          val child = if (path.isEmpty()) key else "$path/$key"
          merge(base?.get(key), local.get(key), cloud.get(key), child, conflicts)?.let {
            out.set(key, it)
          }
        }
        out
      }
      else -> {
        conflicts += path
        local
      }
    }
}
