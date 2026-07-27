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
package ac.shard.monitor.core

import java.util.Locale

enum class MonitorOutputKind(val key: String) {
  ACTIONBAR("actionbar"),
  BOSSBAR("bossbar"),
  SIDEBAR("sidebar"),
  CHAT("chat"),
  TABLIST("tablist");

  val permission: String
    get() = "shard.monitor.output.$key"

  companion object {
    private const val SEPARATOR = ","

    fun parseSet(value: String?): Set<MonitorOutputKind> {
      val parsed =
        value
          ?.split(SEPARATOR)
          ?.mapNotNull { part ->
            entries.firstOrNull {
              it.key.equals(part.trim(), true) || it.name.equals(part.trim(), true)
            }
          }
          .orEmpty()
      return if (parsed.isEmpty()) setOf(ACTIONBAR) else LinkedHashSet(parsed)
    }

    fun store(kinds: Set<MonitorOutputKind>): String =
      kinds.ifEmpty { setOf(ACTIONBAR) }.joinToString(SEPARATOR) { it.name }

    @JvmStatic
    fun fromConfig(value: String?): MonitorOutputKind {
      if (value == null) {
        return ACTIONBAR
      }
      return try {
        valueOf(value.trim().uppercase(Locale.ROOT))
      } catch (_: IllegalArgumentException) {
        ACTIONBAR
      }
    }
  }
}
