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

object LabelKey {
  const val RESERVED_PREFIX = "_"

  const val UNATTRIBUTED = "_unattributed"

  const val MAX_LENGTH = 64

  const val MAX_TITLE_LENGTH = 32

  private const val HASH_SUFFIX_LENGTH = 5
  private const val HASH_MASK = 0xFFFF

  private val ALLOWED = Regex("[^a-z0-9_]")
  private val SEPARATORS = Regex("[\\s\\-.:/]+")
  private val REPEATED_UNDERSCORE = Regex("_{2,}")

  fun isReserved(key: String): Boolean = key.startsWith(RESERVED_PREFIX)

  fun canonical(raw: String): String? {
    val squeezed =
      raw
        .trim()
        .lowercase(Locale.ROOT)
        .replace(SEPARATORS, "_")
        .replace(ALLOWED, "")
        .replace(REPEATED_UNDERSCORE, "_")
        .trim('_')
    return when {
      squeezed.isEmpty() -> null
      squeezed.length <= MAX_LENGTH -> squeezed
      else ->
        squeezed.take(MAX_LENGTH - HASH_SUFFIX_LENGTH).trimEnd('_') +
          "_" +
          "%04x".format(squeezed.hashCode() and HASH_MASK)
    }
  }

  fun title(raw: String): String? =
    raw
      .filterNot { it == '<' || it == '>' || it.isISOControl() }
      .trim()
      .takeIf { it.isNotBlank() }
      ?.take(MAX_TITLE_LENGTH)

  fun canonicalList(
    raw: List<String>,
    onDuplicate: (String, String) -> Unit = { _, _ -> },
  ): List<String> {
    val result = LinkedHashSet<String>(raw.size)
    for (value in raw) {
      val key = canonical(value) ?: continue
      if (!result.add(key)) onDuplicate(value, key)
    }
    return result.toList()
  }
}
