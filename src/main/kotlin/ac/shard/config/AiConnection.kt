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
package ac.shard.config

import ac.shard.http.isSecureEndpoint

data class Backoff(val initialSeconds: Long, val maxSeconds: Long, val multiplier: Double) {
  companion object {
    val DEFAULT = Backoff(initialSeconds = 5, maxSeconds = 60, multiplier = 2.0)
  }
}

data class AiConnection(
  val enabled: Boolean,
  val url: String,
  val key: String,
  val gzip: Boolean,
  val batchMaxSize: Int,
  val batchMaxDelayMs: Long,
  val backoff: Backoff,
) {
  fun deviceUrl(path: String): String? {
    val base = url.trim().trimEnd('/').substringBeforeLast('/', "")
    return if (base.isBlank() || !isSecureEndpoint(base)) null else "$base/device/$path"
  }

  companion object {
    const val DEFAULT_BATCH_MAX_SIZE = 32
    const val DEFAULT_BATCH_MAX_DELAY_MS = 50L
  }
}
