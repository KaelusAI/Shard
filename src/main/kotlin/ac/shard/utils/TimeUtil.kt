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
package ac.shard.utils

import ac.shard.config.LocaleManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object TimeUtil {
  @JvmStatic
  fun formatDuration(millisInput: Long, lm: LocaleManager): String {
    var millis = millisInput
    if (millis < 0) return "0" + lm.getRawMessage(Message.TIME_SECONDS)

    val d = lm.getRawMessage(Message.TIME_DAYS)
    val h = lm.getRawMessage(Message.TIME_HOURS)
    val m = lm.getRawMessage(Message.TIME_MINUTES)
    val s = lm.getRawMessage(Message.TIME_SECONDS)

    val days = TimeUnit.MILLISECONDS.toDays(millis)
    millis -= TimeUnit.DAYS.toMillis(days)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    millis -= TimeUnit.HOURS.toMillis(hours)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    millis -= TimeUnit.MINUTES.toMillis(minutes)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)

    val sb = StringBuilder()
    if (days > 0) sb.append(days).append(d).append(" ")
    if (hours > 0) sb.append(hours).append(h).append(" ")
    if (minutes > 0) sb.append(minutes).append(m).append(" ")
    if (sb.isEmpty() || seconds > 0) {
      sb.append(seconds).append(s)
    }

    return sb.toString().trim()
  }

  @JvmStatic
  fun formatTimeAgo(timestamp: Instant, lm: LocaleManager): String {
    val ago = lm.getRawMessage(Message.TIME_AGO)
    val durationMillis = Duration.between(timestamp, Instant.now()).toMillis()

    val days = TimeUnit.MILLISECONDS.toDays(durationMillis)
    if (days > 0) return days.toString() + lm.getRawMessage(Message.TIME_DAYS) + ago

    val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
    if (hours > 0) return hours.toString() + lm.getRawMessage(Message.TIME_HOURS) + ago

    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
    if (minutes > 0) return minutes.toString() + lm.getRawMessage(Message.TIME_MINUTES) + ago

    return TimeUnit.MILLISECONDS.toSeconds(durationMillis).toString() +
      lm.getRawMessage(Message.TIME_SECONDS) +
      ago
  }

  @JvmStatic
  fun parseDuration(durationStr: String?): Long {
    if (durationStr == null) return 0
    if (
      durationStr.equals("perm", ignoreCase = true) ||
        durationStr.equals("permanent", ignoreCase = true)
    ) {
      return -1
    }

    return try {
      val unit = durationStr[durationStr.length - 1].lowercaseChar()
      val value = durationStr.substring(0, durationStr.length - 1).toLong()
      when (unit) {
        's' -> TimeUnit.SECONDS.toMillis(value)
        'm' -> TimeUnit.MINUTES.toMillis(value)
        'h' -> TimeUnit.HOURS.toMillis(value)
        'd' -> TimeUnit.DAYS.toMillis(value)
        else -> 0
      }
    } catch (ex: Exception) {
      0
    }
  }
}
