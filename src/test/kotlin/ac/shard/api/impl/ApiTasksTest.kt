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
package ac.shard.api.impl

import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.bukkit.plugin.IllegalPluginAccessException
import org.junit.jupiter.api.Test

class ApiTasksTest {
  private val queued = mutableListOf<Runnable>()
  private val scheduler =
    mockk<SchedulerService> {
      every { runAsync(any<Runnable>()) } answers
        {
          queued += firstArg<Runnable>()
          mockk(relaxed = true)
        }
    }
  private val tasks = ApiTasks(scheduler)

  @Test
  fun `a call still waiting at shutdown fails with the disabled state`() {
    val future = tasks.supply { 1 }

    tasks.close()

    val error = assertFailsWith<CompletionException> { future.join() }
    assertIs<IllegalStateException>(error.cause)
  }

  @Test
  fun `a call after shutdown is refused at once`() {
    tasks.close()

    assertFailsWith<IllegalStateException> { tasks.supply { 1 } }
  }

  @Test
  fun `a scheduler that no longer accepts work fails the call instead of hanging`() {
    every { scheduler.runAsync(any<Runnable>()) } throws IllegalPluginAccessException("off")

    val error = assertFailsWith<CompletionException> { tasks.supply { 1 }.join() }
    assertIs<IllegalStateException>(error.cause)
  }

  @Test
  fun `a finished call completes normally`() {
    val future = tasks.supply { 7 }
    queued.single().run()

    assertEquals(7, future.join())
  }
}
