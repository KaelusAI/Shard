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
package ac.shard.editor

private const val MAX_ACTION_LENGTH = 512
private const val MAX_REASON_LENGTH = 200
private const val TEMPBAN_DURATION_INDEX = 2

private const val BROADCAST = "[broadcast] "
private const val WAIT = "[wait] "

private val BARE_ACTIONS = setOf("[alert]", "[log]", "[reset]")
private val KNOWN_COMMANDS = setOf("kick", "ban", "tempban")
private val BAN_DURATION = Regex("""\d{1,6}[smhdw]""")
private val WAIT_DURATION = Regex("""\d{1,6}(ms|s|t)?""")
private val PLAYER_TOKEN = Regex("""<player>|<uuid>""")
private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
private val TAG = Regex("""<[^<>]*>""")
private val SAFE_TAG =
  Regex(
    """/?(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|""" +
      """dark_grey|blue|green|aqua|red|light_purple|yellow|white|bold|b|italic|i|em|underlined|""" +
      """u|strikethrough|st|obfuscated|obf|reset|r|#[0-9a-f]{6}|color:[^<>]{1,32}|""" +
      """colour:[^<>]{1,32}|c:[^<>]{1,32}|gradient(:[^<>]{1,64})?|player|uuid|check_name|vl)""",
    RegexOption.IGNORE_CASE,
  )

internal object PunishmentActionRule {

  fun check(action: String): Verdict {
    val trimmed = action.trim()
    return when {
      trimmed.length > MAX_ACTION_LENGTH -> refuse(trimmed, "longer than $MAX_ACTION_LENGTH")
      CONTROL_CHARS.containsMatchIn(trimmed) -> refuse(trimmed, "carries a control character")
      trimmed in BARE_ACTIONS -> Verdict.Allowed
      trimmed.startsWith("[wait]") -> wait(trimmed)
      trimmed.startsWith("[broadcast]") -> broadcast(trimmed)
      else -> command(trimmed)
    }
  }

  private fun wait(action: String): Verdict {
    val argument = action.removePrefix(WAIT)
    return when {
      !action.startsWith(WAIT) -> refuse(action, "[wait] needs a space before its duration")
      !WAIT_DURATION.matches(argument) ->
        refuse(action, "[wait] takes ticks or seconds, such as 40t, 500ms or 10s")
      else -> Verdict.Allowed
    }
  }

  private fun broadcast(action: String): Verdict {
    val message = action.removePrefix(BROADCAST)
    val unknown = TAG.findAll(message).map { it.value }.firstOrNull { !SAFE_TAG.matches(inner(it)) }
    return when {
      !action.startsWith(BROADCAST) ->
        refuse(action, "[broadcast] needs a space before its message")
      message.isBlank() -> refuse(action, "[broadcast] needs a message")
      unknown != null -> refuse(action, "$unknown is not a formatting tag a broadcast may carry")
      else -> Verdict.Allowed
    }
  }

  private fun inner(tag: String) = tag.removePrefix("<").removeSuffix(">").trim()

  fun needsConfirming(action: String): Boolean {
    val trimmed = action.trim()
    if (
      trimmed in BARE_ACTIONS || trimmed.startsWith("[wait]") || trimmed.startsWith("[broadcast]")
    ) {
      return false
    }
    return trimmed.split(' ').firstOrNull()?.lowercase() !in KNOWN_COMMANDS
  }

  private fun command(action: String): Verdict {
    val parts = action.split(' ').filter { it.isNotEmpty() }
    return when (parts.firstOrNull()?.lowercase()) {
      "kick",
      "ban" -> targeted(action, parts, reasonFrom = 2)
      "tempban" -> tempban(action, parts)
      else -> free(action)
    }
  }

  private fun free(action: String): Verdict =
    if (action.length > MAX_ACTION_LENGTH) {
      refuse(action, "longer than $MAX_ACTION_LENGTH")
    } else {
      Verdict.Allowed
    }

  private fun tempban(action: String, parts: List<String>): Verdict {
    val duration = parts.getOrNull(TEMPBAN_DURATION_INDEX)
    return if (duration == null || !BAN_DURATION.matches(duration)) {
      refuse(action, "tempban takes <player> then a duration such as 20h")
    } else {
      targeted(action, parts, reasonFrom = TEMPBAN_DURATION_INDEX + 1)
    }
  }

  private fun targeted(action: String, parts: List<String>, reasonFrom: Int): Verdict {
    val target = parts.getOrNull(1).orEmpty()
    val reason = parts.drop(reasonFrom).joinToString(" ")
    return when {
      !PLAYER_TOKEN.matches(target) -> refuse(action, "the target must be <player> or <uuid>")
      reason.length > MAX_REASON_LENGTH -> refuse(action, "the reason is too long")
      else -> Verdict.Allowed
    }
  }

  private fun refuse(action: String, reason: String) = Verdict.Refused("\"$action\": $reason")
}
