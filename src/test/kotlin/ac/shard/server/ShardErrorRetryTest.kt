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
package ac.shard.server

import ac.shard.ai.stream.Retry
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ShardErrorRetryTest {

  @Test
  fun `whole request errors carry retry and pause`() {
    val e =
      ShardError.parse(
        402,
        """{"error":{"code":"INSUFFICIENT_CREDITS","message":"no","retry":"wait","retry_after_ms":60000}}""",
      )
    assertEquals(Retry.WAIT, e.retry)
    assertEquals(60_000L, e.retryAfterMs)
    val drop =
      ShardError.parse(400, """{"error":{"code":"INCOMPATIBLE_CLIENT_MODEL","retry":"drop"}}""")
    assertEquals(Retry.DROP, drop.retry)
    assertNull(drop.retryAfterMs)
    assertNull(ShardError.parse(500, """{"error":{"code":"INTERNAL"}}""").retry)
  }

  @Test
  fun `hold only ever extends the cooldown`() {
    val cooldown = ApiCooldown(ac.shard.config.Backoff(1, 60, 2.0), System::currentTimeMillis)
    cooldown.holdFor(30_000)
    cooldown.holdFor(1_000)
    assertTrue(cooldown.isWaiting())
    assertTrue(cooldown.remainingMillis() > 20_000)
  }
}
