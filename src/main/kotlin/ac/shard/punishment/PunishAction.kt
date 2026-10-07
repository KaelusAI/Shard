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

import java.util.Locale

sealed interface PunishAction {
  data object Alert : PunishAction

  data object Log : PunishAction

  data object Reset : PunishAction

  data class Broadcast(val template: String) : PunishAction

  data class Wait(val argument: String, val ticks: Long?) : PunishAction

  data class Console(val template: String) : PunishAction

  companion object {
    private const val BROADCAST = "[broadcast] "
    private const val WAIT = "[wait]"

    fun parse(raw: String): PunishAction? {
      val trimmed = raw.trim()
      val lower = trimmed.lowercase(Locale.ROOT)
      return when {
        lower == "[alert]" -> Alert
        lower == "[log]" -> Log
        lower == "[reset]" -> Reset
        lower.startsWith(BROADCAST) -> Broadcast(trimmed.substring(BROADCAST.length))
        lower.startsWith(WAIT) -> waitOf(trimmed)
        else -> Console(trimmed)
      }
    }

    private fun waitOf(command: String): Wait? {
      val argument = command.substring(WAIT.length).trim().removePrefix("]").trim()
      return argument.takeIf { it.isNotBlank() }?.let { Wait(it, parseWaitTicks(it)) }
    }
  }
}
