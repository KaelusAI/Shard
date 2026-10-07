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

import ac.shard.config.AiConnection
import ac.shard.config.Backoff
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ApiCooldownHoldTest {

  private var now = 1_000_000L
  private val midJitter =
    object : Random() {
      override fun nextBits(bitCount: Int) = 0

      override fun nextDouble() = 0.5
    }
  private val cooldown = ApiCooldown(ac.shard.config.Backoff(5, 60, 2.0), { now }, midJitter)

  @Test
  fun `a new backoff keeps the hold the backend asked for`() {
    cooldown.holdForRefusal()
    val left = cooldown.remainingMillis()

    cooldown.retune(ac.shard.config.Backoff(1, 2, 2.0))

    assertEquals(left, cooldown.remainingMillis())
  }

  @Test
  fun `only a new key or address starts the cooldown over`() {
    fun connection(key: String, backoff: Backoff = Backoff(5, 60, 2.0)) =
      AiConnection(true, "https://a", key, true, 32, 50L, backoff)

    assertSame(
      cooldown,
      cooldownFor(connection("k"), connection("k", Backoff(1, 2, 2.0)), cooldown) { now },
    )
    assertNotSame(cooldown, cooldownFor(connection("k"), connection("other"), cooldown) { now })
  }

  @Test
  fun `a success does not lift a hold the server asked for`() {
    cooldown.holdFor(10_000)
    cooldown.recordSuccess()
    assertTrue(cooldown.isWaiting())
    now += 10_000
    assertFalse(cooldown.isWaiting())
  }

  @Test
  fun `a failure never shortens a longer hold`() {
    cooldown.holdForRefusal()
    cooldown.recordFailure()
    assertEquals(60_000L, cooldown.remainingMillis())
  }

  @Test
  fun `refusals double from a minute to half an hour and a success resets them`() {
    val holds =
      (1..7).map {
        cooldown.holdForRefusal()
        cooldown.remainingMillis().also { now += it }
      }
    assertEquals(
      listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L),
      holds,
    )
    cooldown.recordSuccess()
    cooldown.holdForRefusal()
    assertEquals(60_000L, cooldown.remainingMillis())
  }

  @Test
  fun `consecutive waits grow to a minute and never undercut the server`() {
    val holds =
      (1..8).map {
        cooldown.holdForWait(1_000)
        cooldown.remainingMillis().also { now += it }
      }
    assertEquals(1_000L, holds.first())
    assertEquals(32_000L, holds[5])
    assertEquals(60_000L, holds.last(), "waits cap at a minute")
    cooldown.holdForWait(90_000)
    assertTrue(cooldown.remainingMillis() >= 90_000)
  }

  @Test
  fun `replies that land during one pause raise it by one step only`() {
    repeat(6) { cooldown.holdForWait(1_000) }
    assertEquals(1_000L, cooldown.remainingMillis())
    now += 1_000
    cooldown.holdForWait(1_000)
    assertEquals(2_000L, cooldown.remainingMillis())
  }

  @Test
  fun `a rejected profile keeps escalating across successful replies`() {
    val holds =
      (1..3).map {
        cooldown.recordSuccess()
        cooldown.holdForRejectedProfile()
        cooldown.remainingMillis().also { now += it }
      }
    assertEquals(listOf(60_000L, 120_000L, 240_000L), holds)
    cooldown.profileAccepted()
    cooldown.holdForRejectedProfile()
    assertEquals(60_000L, cooldown.remainingMillis())
  }
}
