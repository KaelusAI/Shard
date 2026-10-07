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
package ac.shard.connect

import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DeviceFlowPollerTest {
  private val delays = mutableListOf<Long>()
  private val queued = ArrayDeque<Runnable>()
  private val scheduler =
    mockk<SchedulerService> {
      every { runLaterAsync(any(), any()) } answers
        {
          queued += firstArg<Runnable>()
          delays += secondArg<Long>()
          mockk(relaxed = true)
        }
    }
  private val service = mockk<ConnectService>()
  private val poller = DeviceFlowPoller(service, scheduler, Logger.getLogger("poller-test"))
  private val later = Instant.now().epochSecond + 600

  private fun runAll() {
    while (queued.isNotEmpty()) queued.removeFirst().run()
  }

  @Test
  fun `slow down never makes polling faster`() {
    every { service.poll("x") } returnsMany
      listOf(PollResult.SlowDown(2), PollResult.SlowDown(10), PollResult.Pending, PollResult.Denied)
    val ends = mutableListOf<DeviceFlowEnd>()

    poller.await("x", 5, later, { true }, ends::add)
    runAll()

    assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L), delays)
    assertEquals(listOf<DeviceFlowEnd>(DeviceFlowEnd.Denied), ends)
  }

  @Test
  fun `a stopped flow neither polls nor reports`() {
    val ends = mutableListOf<DeviceFlowEnd>()

    poller.await("x", 1, later, { false }, ends::add)
    runAll()

    verify(exactly = 0) { service.poll(any()) }
    assertTrue(ends.isEmpty())
  }

  @Test
  fun `the deadline ends the flow without asking the panel`() {
    val ends = mutableListOf<DeviceFlowEnd>()

    poller.await("x", 1, Instant.now().epochSecond - 1, { true }, ends::add)
    runAll()

    verify(exactly = 0) { service.poll(any()) }
    assertEquals(listOf<DeviceFlowEnd>(DeviceFlowEnd.TimedOut), ends)
  }
}
