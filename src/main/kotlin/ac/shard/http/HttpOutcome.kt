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
package ac.shard.http

enum class HttpOutcome {
  OK,
  REJECTED,
  CONFLICT,
  GONE,
  RATE_LIMITED,
  UNAVAILABLE,
  FAILED;

  companion object {
    private const val OK_MIN = 200
    private const val OK_MAX = 299
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val CONFLICT_CODE = 409
    private const val GONE_CODE = 410
    private const val TOO_MANY_REQUESTS = 429
    private const val SERVER_MIN = 500
    private const val SERVER_MAX = 599

    fun of(status: Int): HttpOutcome =
      when (status) {
        in OK_MIN..OK_MAX -> OK
        UNAUTHORIZED,
        FORBIDDEN -> REJECTED
        CONFLICT_CODE -> CONFLICT
        GONE_CODE -> GONE
        TOO_MANY_REQUESTS -> RATE_LIMITED
        in SERVER_MIN..SERVER_MAX -> UNAVAILABLE
        else -> FAILED
      }
  }
}

object PanelReplies {
  const val REJECTED =
    "The panel did not accept this server: the key is unknown or this server's IP is not " +
      "on its allowlist. Check the allowed IPs in the panel or run /shard connect again."
  const val RATE_LIMITED = "Too many attempts. Please wait a few minutes."

  fun failed(status: Int): String = "The panel returned HTTP $status."
}
