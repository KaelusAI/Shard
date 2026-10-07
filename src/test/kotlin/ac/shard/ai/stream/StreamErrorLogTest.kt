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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class StreamErrorLogTest {

  private var now = 0L
  private val lines = mutableListOf<Pair<Level, String>>()
  private val log = StreamErrorLog({ level, message -> lines += level to message }, { now })

  private fun refusal(code: ResponseCode, server: String, retry: Retry? = null) =
    RequestException(code, "x", serverCode = server, httpStatus = code.httpCode, retry = retry)

  @Test
  fun `a persistent refusal is reported at once and then quietly counted`() {
    repeat(3) {
      log.onRequestFailed(refusal(ResponseCode.INSUFFICIENT_CREDITS, "INSUFFICIENT_CREDITS"))
    }
    assertEquals(1, lines.size)
    assertEquals(Level.WARNING, lines.single().first)
    assertTrue("INSUFFICIENT_CREDITS" in lines.single().second)
    now += 5 * 60_000L
    log.onRequestFailed(refusal(ResponseCode.INSUFFICIENT_CREDITS, "INSUFFICIENT_CREDITS"))
    assertTrue("+2 more" in lines.last().second)
  }

  @Test
  fun `a drop refusal counts as persistent whatever its code`() {
    log.onRequestFailed(refusal(ResponseCode.UNKNOWN_ERROR, "SOMETHING_NEW", Retry.DROP))
    assertEquals(1, lines.size)
    log.onRequestFailed(refusal(ResponseCode.UNKNOWN_ERROR, "OTHER_NEW"))
    assertEquals(1, lines.size, "without drop an unknown code is transient")
  }

  @Test
  fun `transient failures speak up only after five in a row and on recovery`() {
    repeat(4) {
      log.onRequestFailed(refusal(ResponseCode.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"))
    }
    assertTrue(lines.isEmpty())
    log.onRequestFailed(refusal(ResponseCode.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"))
    assertEquals(1, lines.size)
    log.onRequestSucceeded()
    assertTrue("work again after 5" in lines.last().second)
  }

  @Test
  fun `the local cooldown refusal is not a server error`() {
    repeat(10) { log.onRequestFailed(RequestException(ResponseCode.WAITING, "wait")) }
    log.onRequestSucceeded()
    assertTrue(lines.isEmpty())
  }

  @Test
  fun `routine item errors are fine level and others warn`() {
    log.onItemError("UNKNOWN_STREAM")
    log.onItemError("INVALID_INPUT")
    assertEquals(listOf(Level.FINE, Level.WARNING), lines.map { it.first })
  }

  @Test
  fun `recovery is reported after a persistent refusal even under five failures`() {
    log.onRequestFailed(refusal(ResponseCode.INSUFFICIENT_CREDITS, "INSUFFICIENT_CREDITS"))
    log.onRequestSucceeded()
    assertEquals(Level.INFO, lines.last().first)
    log.onRequestSucceeded()
    assertEquals(2, lines.size, "one recovery line per streak")
  }

  @Test
  fun `entry errors are reported per model and code`() {
    log.onEntryError("pro", "NON_FINITE_INPUT")
    log.onEntryError("pro", "NON_FINITE_INPUT")
    log.onEntryError("flash", "NON_FINITE_INPUT")
    assertEquals(2, lines.size)
    assertTrue("model pro" in lines[0].second)
  }

  @Test
  fun `network failures keep their cause text`() {
    repeat(5) {
      log.onRequestFailed(
        RequestException(
          ResponseCode.NETWORK_ERROR,
          "Request failed: connection refused",
          serverCode = "NETWORK_ERROR",
        )
      )
    }
    assertTrue("connection refused" in lines.single().second)
  }
}
