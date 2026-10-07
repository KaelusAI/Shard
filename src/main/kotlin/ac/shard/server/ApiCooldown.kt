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

import ac.shard.config.Backoff
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@Suppress("TooManyFunctions")
class ApiCooldown(
  backoff: Backoff,
  private val clock: () -> Long,
  private val random: Random = Random.Default,
) {
  @Volatile private var settings = backoff
  private val nextAttempt = AtomicLong(0)
  private val currentBackoff = AtomicLong(initial())
  private val heldUntil = AtomicLong(0)
  private val refusals = AtomicInteger()
  private val waits = AtomicInteger()
  private val rejections = AtomicInteger()

  fun isWaiting(): Boolean = clock() < maxOf(nextAttempt.get(), heldUntil.get())

  fun remainingMillis(): Long =
    (maxOf(nextAttempt.get(), heldUntil.get()) - clock()).coerceAtLeast(0)

  fun retune(backoff: Backoff) {
    settings = backoff
  }

  fun recordSuccess() {
    currentBackoff.set(initial())
    nextAttempt.set(0)
    refusals.set(0)
    waits.set(0)
  }

  fun recordFailure() {
    val backoff = settings
    val max = backoff.maxSeconds * MILLIS_PER_SECOND
    val currentDuration = currentBackoff.get().coerceIn(initial(), max)
    extend(nextAttempt, clock() + currentDuration)
    currentBackoff.set(minOf((currentDuration * backoff.multiplier).toLong(), max))
  }

  private fun initial(): Long = settings.initialSeconds * MILLIS_PER_SECOND

  fun holdFor(millis: Long) {
    extend(heldUntil, clock() + millis)
  }

  fun holdForWait(retryAfterMs: Long) {
    val n = step(waits, WAIT_DOUBLINGS)
    val escalated = minOf(retryAfterMs shl (n - 1), MAX_WAIT_MS)
    holdFor(maxOf(retryAfterMs, jittered(escalated)))
  }

  fun holdForRefusal(retryAfterMs: Long = 0) {
    holdFor(maxOf(retryAfterMs, refusalHold(step(refusals, REFUSAL_DOUBLINGS))))
  }

  fun holdForRejectedProfile() {
    holdFor(refusalHold(step(rejections, REFUSAL_DOUBLINGS)))
  }

  fun profileAccepted() {
    rejections.set(0)
  }

  private fun refusalHold(n: Int): Long =
    minOf(FIRST_REFUSAL_HOLD_MS shl (n - 1), MAX_REFUSAL_HOLD_MS)

  private fun step(counter: AtomicInteger, cap: Int): Int =
    if (clock() < heldUntil.get() && counter.get() > 0) {
      counter.get().coerceAtMost(cap)
    } else {
      counter.incrementAndGet().coerceAtMost(cap)
    }

  private fun jittered(millis: Long): Long =
    (millis * (1.0 + (random.nextDouble() * 2 - 1) * JITTER)).toLong()

  private fun extend(target: AtomicLong, until: Long) {
    target.getAndUpdate { maxOf(it, until) }
  }

  private companion object {
    const val MILLIS_PER_SECOND = 1000L
    const val WAIT_DOUBLINGS = 7
    const val REFUSAL_DOUBLINGS = 6
    const val MAX_WAIT_MS = 60_000L
    const val FIRST_REFUSAL_HOLD_MS = 60_000L
    const val MAX_REFUSAL_HOLD_MS = 30 * 60_000L
    const val JITTER = 0.2
  }
}
