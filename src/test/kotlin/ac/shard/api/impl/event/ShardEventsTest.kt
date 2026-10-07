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
package ac.shard.api.impl.event

import ac.shard.api.event.Priority
import ac.shard.api.event.ShardEvent
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin

class ShardEventsTest {
  private open class Ping : ShardEvent

  private class LoudPing : Ping()

  private class Vote : CancellableEvent(), ShardEvent

  private val events = ShardEvents(Logger.getLogger("events-test"))

  private fun plugin(enabled: Boolean = true): Plugin =
    mockk(relaxed = true) {
      every { isEnabled } returns enabled
      every { name } returns "Integration"
      every { logger } returns Logger.getLogger("integration-test")
    }

  @AfterTest
  fun close() {
    events.shutdown()
  }

  @Test
  fun `handlers run by priority, ties in subscription order, monitors last`() {
    val owner = plugin()
    val order = mutableListOf<String>()
    events.subscription(owner, Ping::class.java).monitor().subscribe { order += "monitor" }
    events.subscription(owner, Ping::class.java).priority(Priority.LATE).subscribe {
      order += "late"
    }
    events.subscribe(owner, Ping::class.java) { order += "normal-1" }
    events.subscribe(owner, Ping::class.java) { order += "normal-2" }
    events.subscription(owner, Ping::class.java).priority(Priority.EARLY).subscribe {
      order += "early"
    }

    events.fire(Ping())

    assertEquals(listOf("early", "normal-1", "normal-2", "late", "monitor"), order)
  }

  @Test
  fun `a supertype subscription receives subtypes`() {
    val owner = plugin()
    var seen = 0
    events.subscribe(owner, Ping::class.java) { seen++ }

    events.fire(LoudPing())

    assertEquals(1, seen)
    assertTrue(events.wants(LoudPing::class.java))
    assertFalse(events.wants(Vote::class.java))
  }

  @Test
  fun `ignoreCancelled skips an already cancelled event`() {
    val owner = plugin()
    var reached = false
    events.subscription(owner, Vote::class.java).priority(Priority.EARLY).subscribe {
      it.setCancelled(true)
    }
    events.subscription(owner, Vote::class.java).ignoreCancelled(true).subscribe { reached = true }

    val vote = events.fire(Vote())

    assertTrue(vote.isCancelled)
    assertFalse(reached)
  }

  @Test
  fun `a monitor sees the outcome but cannot change it`() {
    val owner = plugin()
    events.subscription(owner, Vote::class.java).monitor().subscribe { it.setCancelled(true) }

    val vote = events.fire(Vote())

    assertFalse(vote.isCancelled)
    assertFailsWith<IllegalStateException> { vote.setCancelled(true) }
  }

  @Test
  fun `a throwing handler does not stop the others`() {
    val owner = plugin()
    var reached = false
    events.subscription(owner, Ping::class.java).priority(Priority.EARLY).subscribe {
      error("boom")
    }
    events.subscribe(owner, Ping::class.java) { reached = true }

    events.fire(Ping())

    assertTrue(reached)
  }

  @Test
  fun `subscriptions close when their owner disables`() {
    val owner = plugin()
    val other = plugin()
    var mine = 0
    var theirs = 0
    events.subscribe(owner, Ping::class.java) { mine++ }
    events.subscribe(other, Ping::class.java) { theirs++ }

    events.onPluginDisable(mockk<PluginDisableEvent> { every { plugin } returns owner })
    events.fire(Ping())

    assertEquals(0, mine)
    assertEquals(1, theirs)
    assertTrue(events.subscriptions(owner).isEmpty())
  }

  @Test
  fun `a disabled plugin cannot subscribe`() {
    assertFailsWith<IllegalArgumentException> {
      events.subscribe(plugin(enabled = false), Ping::class.java) {}
    }
  }

  @Test
  fun `published events reach handlers on the event thread`() {
    val owner = plugin()
    val latch = CountDownLatch(1)
    var thread = ""
    events.subscribe(owner, Ping::class.java) {
      thread = Thread.currentThread().name
      latch.countDown()
    }

    events.publish(Ping())

    assertTrue(latch.await(2, TimeUnit.SECONDS))
    assertEquals("Shard-Events", thread)
  }
}
