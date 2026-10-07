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

import ac.shard.ai.label.DetectionKey
import java.util.Locale

data class MonitorSettings(
  val mode: MonitorMode,
  val theme: MonitorTheme,
  val showPing: Boolean,
  val showDmg: Boolean,
  val showTrend: Boolean,
  val showName: MonitorNameMode,
  val outputs: Set<MonitorOutputKind> = setOf(MonitorOutputKind.ACTIONBAR),
  val chatStyle: MonitorChatStyle = MonitorChatStyle.SUMMARY,
  val showCollect: Boolean = true,
  val showInference: Boolean = true,
  val labelFocus: String = LabelFocus.AUTO,
  val models: String = ModelFilter.ALL,
)

object ModelFilter {
  const val ALL = "all"

  const val PRIMARY = "primary"

  const val UNSET = ""

  fun parse(raw: String?): String? {
    val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
    return when (value) {
      ALL -> ALL
      PRIMARY -> PRIMARY
      else -> {
        val ids = value.split(',').map(String::trim).filter(String::isNotEmpty)
        ids
          .takeIf { list ->
            list.isNotEmpty() && list.all { ID.matches(it) && it != ALL && it != PRIMARY }
          }
          ?.distinct()
          ?.sorted()
          ?.joinToString(",")
          ?.takeIf { it.length <= MAX_LENGTH }
      }
    }
  }

  fun shows(value: String, modelId: String, primary: Boolean): Boolean =
    when (value) {
      ALL,
      UNSET -> true
      PRIMARY -> primary
      else -> modelId.lowercase(Locale.ROOT) in value.split(',')
    }

  fun describe(value: String): String = value

  fun ids(value: String): List<String> =
    if (value == ALL || value == PRIMARY || value == UNSET) emptyList() else value.split(',')

  const val MAX_LENGTH = 255

  private val ID = Regex("[a-z0-9_.-]{1,64}")
}

object LabelFocus {
  const val AUTO = ""

  const val STRONGEST = "strongest"

  const val ALL = "all"

  fun parse(raw: String?): String? {
    val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
    return when (value) {
      "auto",
      "rotate",
      AUTO -> AUTO
      "off",
      "strongest",
      "top" -> STRONGEST
      "every",
      "both",
      ALL -> ALL
      else -> DetectionKey.selector(value)
    }
  }

  fun rotates(value: String): Boolean = value == AUTO || value == ALL

  fun describe(value: String): String =
    when (value) {
      AUTO -> "auto"
      STRONGEST -> "strongest"
      else -> value
    }
}
