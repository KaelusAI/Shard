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
package ac.shard.config

import java.util.Locale
import org.spongepowered.configurate.ConfigurationNode

data class ModelOverride(val enabled: Boolean = true, val disabledRoles: Set<String> = emptySet()) {
  companion object {
    private val ROLE_KEYS = listOf("alert", "mitigate", "punish")
    private val OFF_VALUES = setOf("off", "false", "no")

    fun parse(node: ConfigurationNode): Map<String, ModelOverride> {
      if (!node.isMap) return emptyMap()
      return node.childrenMap().entries.associate { (key, child) ->
        key.toString() to
          if (child.isMap) {
            ModelOverride(
              enabled = !switchedOff(child.node("enabled")),
              disabledRoles = ROLE_KEYS.filter { switchedOff(child.node(it)) }.toSet(),
            )
          } else {
            ModelOverride(enabled = !switchedOff(child))
          }
      }
    }

    private fun switchedOff(node: ConfigurationNode): Boolean =
      node.raw() == false || node.getString("").trim().lowercase(Locale.ROOT) in OFF_VALUES
  }
}

data class LocalAiSettings(
  val split: Boolean,
  val names: Map<String, String>,
  val models: Map<String, ModelOverride> = emptyMap(),
  val generation: Long = 0,
) {
  fun narrowing(): Map<String, Any> =
    mapOf(
      "models" to
        models
          .filterValues { !it.enabled || it.disabledRoles.isNotEmpty() }
          .toSortedMap()
          .mapValues { (_, o) ->
            if (!o.enabled) "off" else o.disabledRoles.sorted().associateWith { false }
          }
    )
}
