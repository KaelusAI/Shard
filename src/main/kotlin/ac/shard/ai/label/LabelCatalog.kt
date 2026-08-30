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
package ac.shard.ai.label

class LabelCatalog(
  private val local: () -> Map<String, String>,
  private val fromServer: () -> Map<String, String> = ::emptyMap,
) {

  fun displayName(key: String): String {
    val canonical = LabelKey.canonical(key) ?: key
    return named(local(), canonical) ?: named(fromServer(), canonical) ?: canonical
  }

  private fun named(source: Map<String, String>, key: String): String? =
    source[key]?.takeIf { it.isNotBlank() }

  fun visible(buffers: Map<String, Double>): List<String> =
    buffers.entries
      .filterNot { LabelKey.isReserved(it.key) }
      .sortedByDescending { it.value }
      .map { it.key }

  fun leading(buffers: Map<String, Double>): String? =
    buffers.entries
      .filterNot { LabelKey.isReserved(it.key) }
      .maxByOrNull { it.value }
      ?.takeIf { it.value > 0.0 }
      ?.key

  fun format(keys: Collection<String>): String =
    keys.filterNot(LabelKey::isReserved).joinToString(", ", transform = ::displayName)

  fun decorate(checkName: String, keys: Collection<String>): String {
    val labels = format(keys)
    return if (labels.isEmpty()) checkName else "$checkName ($labels)"
  }
}
