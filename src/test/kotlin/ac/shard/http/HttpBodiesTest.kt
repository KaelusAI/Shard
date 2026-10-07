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

import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HttpBodiesTest {

  private fun feed(limit: Int, vararg chunks: String): Pair<String?, Throwable?> {
    val subscriber = HttpBodies.text(limit).apply(mockk(relaxed = true))
    var cancelled = false
    subscriber.onSubscribe(
      object : Flow.Subscription {
        override fun request(n: Long) = Unit

        override fun cancel() {
          cancelled = true
        }
      }
    )
    for (chunk in chunks) subscriber.onNext(listOf(ByteBuffer.wrap(chunk.toByteArray())))
    subscriber.onComplete()
    val future = subscriber.body.toCompletableFuture()
    return try {
      future.get() to null
    } catch (e: ExecutionException) {
      assertTrue(cancelled, "an oversized body must stop the download")
      null to e.cause
    }
  }

  @Test
  fun `a body within the limit arrives whole`() {
    assertEquals("abcdef" to null, feed(6, "abc", "def"))
  }

  @Test
  fun `a body over the limit fails instead of being cut`() {
    assertIs<BodyTooLargeException>(feed(5, "abc", "def").second)
  }

  @Test
  fun `gunzip stops at the limit`() {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(ByteArray(64)) }
    assertEquals(64, HttpBodies.gunzip(out.toByteArray(), 64).size)
    assertFailsWith<BodyTooLargeException> { HttpBodies.gunzip(out.toByteArray(), 63) }
  }

  @Test
  fun `statuses fall into one outcome each`() {
    assertEquals(HttpOutcome.OK, HttpOutcome.of(204))
    assertEquals(HttpOutcome.REJECTED, HttpOutcome.of(401))
    assertEquals(HttpOutcome.REJECTED, HttpOutcome.of(403))
    assertEquals(HttpOutcome.GONE, HttpOutcome.of(410))
    assertEquals(HttpOutcome.RATE_LIMITED, HttpOutcome.of(429))
    assertEquals(HttpOutcome.UNAVAILABLE, HttpOutcome.of(503))
    assertEquals(HttpOutcome.FAILED, HttpOutcome.of(418))
  }
}
