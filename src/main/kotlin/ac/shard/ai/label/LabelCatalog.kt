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

import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.StreamProfile

@Suppress("TooManyFunctions")
class LabelCatalog(
  private val local: () -> Map<String, String>,
  private val fromServer: () -> Map<String, String> = ::emptyMap,
  private val profile: () -> StreamProfile? = { null },
) {

  fun displayName(key: String): String {
    val address = DetectionKey.parseAddress(key) ?: return primaryName(key)
    val current = profile()
    val model = current?.model(address.model)
    return when {
      model == null -> key
      model.id == current.primary.id -> primaryName(address.label)
      LabelKey.isReserved(address.label) -> model.displayTitle
      else -> model.displayTitle + " " + secondaryName(model, address.label)
    }
  }

  fun ownerTitle(key: String): String? {
    val address = DetectionKey.parseAddress(key) ?: return null
    val current = profile()
    val model = current?.model(address.model)?.takeIf { it.id != current.primary.id }
    return model?.displayTitle
  }

  fun ownName(key: String): String {
    val address = DetectionKey.parseAddress(key) ?: return primaryName(key)
    return profile()?.model(address.model)?.let { secondaryName(it, address.label) }
      ?: address.label
  }

  fun hidden(key: String): Boolean {
    val address = DetectionKey.parseAddress(key) ?: return LabelKey.isReserved(key)
    val current = profile()
    val model = current?.model(address.model)
    return LabelKey.isReserved(address.label) && (model == null || model.id == current.primary.id)
  }

  private fun secondaryName(model: ModelSpec, raw: String): String {
    val label = LabelKey.canonical(raw) ?: raw
    return named(model.labelTitles, label) ?: label
  }

  private fun primaryName(key: String): String {
    val canonical = LabelKey.canonical(key) ?: key
    return named(local(), canonical) ?: named(fromServer(), canonical) ?: canonical
  }

  private fun named(source: Map<String, String>, key: String): String? =
    source[key]?.takeIf { it.isNotBlank() }

  fun visible(buffers: Map<String, Double>): List<String> =
    buffers.entries.filterNot { hidden(it.key) }.sortedByDescending { it.value }.map { it.key }

  fun leading(buffers: Map<String, Double>): String? =
    buffers.entries
      .filterNot { hidden(it.key) }
      .maxByOrNull { it.value }
      ?.takeIf { it.value > 0.0 }
      ?.key

  fun format(keys: Collection<String>): String =
    keys.filterNot(::hidden).joinToString(", ", transform = ::displayName)

  fun decorate(checkName: String, keys: Collection<String>): String {
    val labels = format(keys)
    return if (labels.isEmpty()) checkName else "$checkName ($labels)"
  }
}
