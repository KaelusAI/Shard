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
package ac.shard.panel

import ac.shard.ShardReloader
import ac.shard.connect.ConnectService
import ac.shard.connect.Credentials
import ac.shard.connect.CredentialsStore
import ac.shard.connect.DeviceFlowPoller
import ac.shard.connect.LinkIntent
import ac.shard.connect.LinkResult
import ac.shard.connect.PollResult
import ac.shard.connect.StartResult
import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServerLinkTest {
  @TempDir lateinit var dir: Path

  private val queued = ArrayDeque<Runnable>()
  private var syncRuns = 0
  private val scheduler =
    mockk<SchedulerService> {
      every { runAsync(any<Runnable>()) } answers
        {
          queued += firstArg<Runnable>()
          mockk(relaxed = true)
        }
      every { runLaterAsync(any(), any()) } answers
        {
          queued += firstArg<Runnable>()
          mockk(relaxed = true)
        }
      every { runSync(any<Runnable>()) } answers
        {
          syncRuns++
          mockk(relaxed = true)
        }
    }
  private val service =
    mockk<ConnectService> {
      every { cancel(any()) } just runs
      every { revoke(any()) } returns ac.shard.connect.RevokeResult.Revoked
    }
  private var stored: Credentials? = null
  private val credentials =
    mockk<CredentialsStore> {
      every { instanceId() } returns "i"
      every { read() } answers { stored }
      every { write(any()) } answers { stored = firstArg() }
      every { clear() } answers
        {
          stored = null
          true
        }
    }
  private val pending by lazy { PendingLinkStore(dir.toFile()) }
  private val link by lazy {
    ServerLink(
      service,
      credentials,
      mockk<ShardReloader>(relaxed = true),
      scheduler,
      pending,
      DeviceFlowPoller(service, scheduler, Logger.getLogger("link-test")),
    )
  }
  private val steps = mutableListOf<LinkStep>()

  private fun started(code: String) =
    StartResult.Started(code, "u-$code", "https://a.example", "https://a.example/$code", 600, 1)

  private fun creds(name: String) = Credentials("k", null, name, null, null)

  private fun runAll() {
    while (queued.isNotEmpty()) queued.removeFirst().run()
  }

  @Test
  fun `a setup cancelled while starting shows nothing and keeps no code`() {
    every { service.start(any(), LinkIntent.SETUP) } returns started("a")

    link.begin(LinkIntent.SETUP, steps::add)
    assertTrue(link.cancel(LinkIntent.SETUP))
    runAll()

    assertTrue(steps.isEmpty())
    assertNull(pending.read())
    verify { service.cancel("a") }
  }

  @Test
  fun `the latest begin wins and the earlier code is cancelled`() {
    every { service.start(any(), LinkIntent.SETUP) } returnsMany listOf(started("a"), started("b"))
    every { service.poll(any()) } returns PollResult.Pending

    assertFalse(link.begin(LinkIntent.SETUP) {})
    assertTrue(link.begin(LinkIntent.SETUP, steps::add))
    queued.removeFirst().run()
    queued.removeFirst().run()

    verify { service.cancel("a") }
    assertEquals("b", pending.read()?.deviceCode)
    assertEquals(1, steps.size)
  }

  @Test
  fun `an approval writes the key once and clears the pending code`() {
    every { service.start(any(), LinkIntent.SETUP) } returns started("a")
    every { service.poll("a") } returns PollResult.Approved(creds("s"))

    link.begin(LinkIntent.SETUP, steps::add)
    runAll()

    assertEquals("s", stored?.serverName)
    assertNull(pending.read())
    assertEquals(1, syncRuns)
    assertEquals(LinkStep.Linked("s"), steps.last())
    verify(exactly = 1) { credentials.write(any()) }
  }

  @Test
  fun `unlinking stops a running setup and its approval is ignored`() {
    stored = creds("old")
    every { service.start(any(), LinkIntent.SETUP) } returns started("a")
    every { service.poll("a") } returns PollResult.Approved(creds("new"))

    link.begin(LinkIntent.SETUP, steps::add)
    queued.removeFirst().run()
    assertTrue(link.unlink(canRevoke = false) {})
    runAll()

    assertNull(stored)
    assertNull(pending.read())
    verify(exactly = 0) { service.poll(any()) }
    verify { service.cancel("a") }
    assertEquals(1, steps.size)
  }

  @Test
  fun `a redeem answered after an unlink writes nothing`() {
    stored = creds("old")
    every { service.redeem(any(), any(), any()) } returns LinkResult.Linked(creds("new"))
    val results = mutableListOf<LinkResult>()

    link.redeem("c", results::add)
    link.unlink(canRevoke = false) {}
    runAll()

    assertNull(stored)
    assertTrue(results.isEmpty())
  }

  @Test
  fun `cancelling setup leaves a connect flow alone`() {
    every { service.start(any(), LinkIntent.CONNECT) } returns started("a")

    link.begin(LinkIntent.CONNECT) {}

    assertFalse(link.cancel(LinkIntent.SETUP))
    assertEquals(LinkIntent.CONNECT, link.pendingIntent())
  }

  @Test
  fun `a connect flow keeps no pending code on disk`() {
    every { service.start(any(), LinkIntent.CONNECT) } returns started("a")
    every { service.poll(any()) } returns PollResult.Pending

    link.begin(LinkIntent.CONNECT, steps::add)
    queued.removeFirst().run()

    assertNull(pending.read())
    assertTrue(steps.single() is LinkStep.NeedsApproval)
  }

  @Test
  fun `an expired pending code is dropped on resume`() {
    pending.write(PendingLink("a", "u", "https://a.example", Instant.now().epochSecond - 1, 1))

    assertFalse(link.resume {})
    assertFalse(File(dir.toFile(), "linking.yml").exists())
  }
}
