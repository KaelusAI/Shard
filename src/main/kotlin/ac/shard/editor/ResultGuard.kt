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

import java.time.Duration
import java.time.Instant

private const val FRESHNESS_MINUTES = 10L

internal class ResultGuard(
  private val window: Duration = Duration.ofMinutes(FRESHNESS_MINUTES),
  private val now: () -> Instant = Instant::now,
) {
  private val seen = mutableSetOf<String>()

  @Synchronized
  fun accept(resultId: String, issuedAt: Instant): Verdict {
    val age = Duration.between(issuedAt, now())
    return when {
      resultId.isBlank() -> Verdict.Refused("the result carries no id")
      age > window -> Verdict.Refused("the result is ${age.toMinutes()} minutes old")
      age < window.negated() -> Verdict.Refused("the result is dated in the future")
      !seen.add(resultId) -> Verdict.Refused("result $resultId was already applied")
      else -> Verdict.Allowed
    }
  }
}
