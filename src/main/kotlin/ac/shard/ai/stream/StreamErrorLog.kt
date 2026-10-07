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
package ac.shard.ai.stream

import ac.shard.server.AIServer.RequestException
import ac.shard.server.AIServer.ResponseCode
import java.util.logging.Level

class StreamErrorLog(
  private val log: (Level, String) -> Unit,
  private val clock: () -> Long,
  private val quietMs: Long = QUIET_MS,
) {
  private val lastReport = HashMap<String, Long>()
  private val suppressed = HashMap<String, Int>()
  private var failures = 0
  private var lastCause = ""
  private var reportedStreak = false

  @Synchronized
  fun onRequestFailed(cause: Throwable) {
    val request = cause as? RequestException
    if (request?.code == ResponseCode.WAITING) return
    failures++
    val code = request?.serverCode ?: cause.javaClass.simpleName
    lastCause = code
    val detail = describe(request, cause)
    if (request != null && persistent(request)) {
      reportedStreak = true
      report("request:$code", Level.WARNING) {
        "[AI] Inference server refused the request: $detail"
      }
    } else if (failures >= TRANSIENT_THRESHOLD) {
      reportedStreak = true
      report("transient:$code", Level.WARNING) {
        "[AI] Inference requests keep failing ($failures in a row): $detail"
      }
    }
  }

  @Synchronized
  fun onRequestSucceeded() {
    if (reportedStreak) {
      log(Level.INFO, "[AI] Inference requests work again after $failures failures ($lastCause)")
    }
    failures = 0
    reportedStreak = false
  }

  @Synchronized
  fun onItemError(code: String) {
    val level = if (code in ROUTINE_ITEM_CODES) Level.FINE else Level.WARNING
    report("item:$code", level) { "[AI] Inference server rejected a chunk: $code" }
  }

  @Synchronized
  fun onEntryError(model: String, code: String) {
    report("entry:$model:$code", Level.WARNING) {
      "[AI] Inference server could not score model $model: $code"
    }
  }

  @Synchronized
  fun onBadReply(reason: String) {
    report("reply", Level.WARNING) { "[AI] Inference reply could not be read: $reason" }
  }

  private fun report(key: String, level: Level, message: () -> String) {
    val now = clock()
    val last = lastReport[key]
    if (last != null && now - last < quietMs) {
      suppressed.merge(key, 1, Int::plus)
      return
    }
    lastReport[key] = now
    val skipped = suppressed.remove(key)
    log(level, message() + (skipped?.let { " (+$it more since the last report)" } ?: ""))
  }

  private fun describe(request: RequestException?, cause: Throwable): String =
    if (request == null) {
      cause.javaClass.simpleName + (cause.message?.let { ": " + it.take(MAX_MESSAGE) } ?: "")
    } else {
      buildString {
        append(request.serverCode ?: request.code.name)
        request.httpStatus?.takeIf { it > 0 }?.let { append(" (HTTP ").append(it).append(')') }
        (request.serverMessage ?: request.message)?.let {
          append(": ").append(it.take(MAX_MESSAGE))
        }
        request.retry?.let { append(" [retry ").append(it.wire).append(']') }
      }
    }

  private fun persistent(request: RequestException): Boolean =
    request.retry == Retry.DROP ||
      request.code in PERSISTENT_CODES ||
      request.serverCode in PERSISTENT_SERVER_CODES

  private companion object {
    const val QUIET_MS = 5 * 60_000L
    const val TRANSIENT_THRESHOLD = 5
    const val MAX_MESSAGE = 200
    val ROUTINE_ITEM_CODES = setOf("UNKNOWN_STREAM", "STREAM_LIMIT", "STALE_PROFILE")
    val PERSISTENT_CODES =
      setOf(
        ResponseCode.UNAUTHORIZED,
        ResponseCode.INSUFFICIENT_CREDITS,
        ResponseCode.BAD_REQUEST,
        ResponseCode.PAYLOAD_TOO_LARGE,
        ResponseCode.NOT_FOUND,
      )
    val PERSISTENT_SERVER_CODES = setOf("INCOMPATIBLE_CLIENT_MODEL", "MANIFEST_LIMIT")
  }
}
