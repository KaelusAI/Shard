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

import java.util.Locale

data class DetectionKey(val model: String, val label: String) {
  fun address(): String = "$model/$label"

  companion object {
    fun parseAddress(address: String): DetectionKey? {
      val slash = address.indexOf('/')
      if (slash <= 0 || slash == address.length - 1) return null
      return DetectionKey(address.substring(0, slash), address.substring(slash + 1))
    }

    fun selector(raw: String): String? {
      val value = raw.trim().lowercase(Locale.ROOT)
      val key = parseAddress(value) ?: return LabelKey.canonical(value)
      val label = LabelKey.canonical(key.label)?.takeIf { MODEL_ID.matches(key.model) }
      return label?.let { DetectionKey(key.model, it).address() }
    }

    private val MODEL_ID = Regex("[a-z0-9_]{1,64}")
  }
}
