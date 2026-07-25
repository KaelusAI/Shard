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

import ac.shard.config.ConfigView
import java.util.Locale

data class MonitorThemeEntry(val templates: Map<MonitorToken, String>, val separator: String)

class MonitorThemeTable(private val entries: Map<MonitorTheme, MonitorThemeEntry>) {
  fun entry(theme: MonitorTheme): MonitorThemeEntry =
    entries[theme] ?: entries.getValue(MonitorTheme.CALM)

  fun template(theme: MonitorTheme, token: MonitorToken): String =
    entry(theme).templates[token] ?: FALLBACK_TEMPLATES.getValue(token)

  fun separator(theme: MonitorTheme): String = entry(theme).separator

  companion object {
    fun from(config: ConfigView): MonitorThemeTable =
      MonitorThemeTable(MonitorTheme.entries.associateWith { readEntry(config, it) })

    private fun readEntry(config: ConfigView, theme: MonitorTheme): MonitorThemeEntry {
      val path = "theme.${theme.name.lowercase(Locale.ROOT)}"
      val templates =
        MonitorToken.entries.associateWith { token ->
          config.getString("$path.${token.key}", FALLBACK_TEMPLATES.getValue(token))
        }
      return MonitorThemeEntry(templates, config.getString("$path.sep", DEFAULT_THEME_SEPARATOR))
    }

    private val FALLBACK_TEMPLATES =
      mapOf(
        MonitorToken.NAME to "<gray>@{name}</gray>",
        MonitorToken.PROB to "{prob}%",
        MonitorToken.TREND to "{trend}",
        MonitorToken.BUFFER to "◆ {buffer}",
        MonitorToken.PING to "Ping {ping}ms",
        MonitorToken.DMG to "Dmg {dmg}x",
        MonitorToken.PROB90 to "<color:#F87171>90+ {prob90}</color>",
      )
  }
}

internal const val DEFAULT_THEME_SEPARATOR = "<dark_gray>•</dark_gray>"
