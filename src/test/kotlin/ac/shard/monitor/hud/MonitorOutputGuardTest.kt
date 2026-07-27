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
package ac.shard.monitor.hud

import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorOutputKind
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.spongepowered.configurate.CommentedConfigurationNode

class MonitorOutputGuardTest {
  private val viewerId = UUID.randomUUID()
  private val runtimeConfig =
    MonitorHudRuntimeConfig.from(
      ConfigView(CommentedConfigurationNode.root()),
      2,
      Logger.getLogger("guard-config"),
    )

  private class CountingHandler : Handler() {
    var records = 0

    override fun publish(record: LogRecord?) {
      records++
    }

    override fun flush() = Unit

    override fun close() = Unit
  }

  private class FakeOutput : MonitorOutput {
    var attachThrows = false
    var attachResult = true
    var renderFailures = 0
    var renders = 0

    override val kind = MonitorOutputKind.BOSSBAR

    override val capabilities =
      MonitorOutputCapabilities(
        maxTargets = 1,
        claimsClientSlot = false,
        eventDriven = false,
        requiresClear = true,
      )

    override fun isAvailable(): Boolean = true

    override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
      MonitorOutputPolicy(0, 0)

    override fun attach(context: MonitorRenderContext): Boolean {
      if (attachThrows) {
        error("attach boom")
      }
      return attachResult
    }

    override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) {
      renders++
      if (renderFailures > 0) {
        renderFailures--
        error("render boom")
      }
    }

    override fun clear(context: MonitorRenderContext) = Unit

    override fun detach(context: MonitorRenderContext) = Unit
  }

  private fun context(sessionId: Long = 1L): MonitorRenderContext {
    val viewer = mockk<Player>(relaxed = true)
    every { viewer.uniqueId } returns viewerId
    every { viewer.name } returns "Admin"
    return MonitorRenderContext(
      viewer,
      viewerId,
      sessionId,
      MonitorChatStyle.SUMMARY,
      runtimeConfig,
    )
  }

  private fun payload(): MonitorRenderPayload =
    MonitorRenderPayload(
      listOf(
        MonitorFrame(
          targetId = UUID.randomUUID(),
          targetName = "Steve",
          headline = "x",
          placeholders = emptyMap(),
          progress = 0f,
          severity = MonitorSeverity.CALM,
          dataPresent = true,
          aiActive = true,
        )
      ),
      emptyMap(),
    )

  @Test
  fun `three consecutive render failures trip the breaker and notify once`() {
    val delegate = FakeOutput().apply { renderFailures = 5 }
    var notifications = 0
    val guard =
      MonitorOutputGuard(delegate, Logger.getLogger("guard-trip")) { _, _, _, _ -> notifications++ }
    val context = context()

    guard.attach(context)
    repeat(5) { guard.render(context, payload()) }

    assertEquals(1, notifications)
    assertEquals(3, delegate.renders)
  }

  @Test
  fun `a successful render resets the failure counter`() {
    val delegate = FakeOutput()
    var notifications = 0
    val guard =
      MonitorOutputGuard(delegate, Logger.getLogger("guard-reset")) { _, _, _, _ ->
        notifications++
      }
    val context = context()

    guard.attach(context)
    repeat(4) {
      delegate.renderFailures = 1
      guard.render(context, payload())
      guard.render(context, payload())
    }

    assertEquals(0, notifications)
    assertEquals(8, delegate.renders)
  }

  @Test
  fun `a stale detach leaves the live session rendering`() {
    val delegate = FakeOutput()
    val guard = MonitorOutputGuard(delegate, Logger.getLogger("guard-stale")) { _, _, _, _ -> }
    val stale = context(sessionId = 1L)
    val live = context(sessionId = 2L)

    guard.attach(stale)
    guard.attach(live)
    repeat(2) { guard.render(live, payload()) }
    guard.detach(stale)
    repeat(2) { guard.render(live, payload()) }

    assertEquals(4, delegate.renders)
  }

  @Test
  fun `a stale detach cannot un-trip the live session`() {
    val delegate = FakeOutput().apply { renderFailures = 99 }
    var notifications = 0
    val guard =
      MonitorOutputGuard(delegate, Logger.getLogger("guard-untrip")) { _, _, _, _ ->
        notifications++
      }
    val stale = context(sessionId = 1L)
    val live = context(sessionId = 2L)

    guard.attach(stale)
    guard.attach(live)
    repeat(3) { guard.render(live, payload()) }
    guard.detach(stale)
    repeat(3) { guard.render(live, payload()) }

    assertEquals(1, notifications)
    assertEquals(3, delegate.renders)
  }

  @Test
  fun `an attach that throws reports failure and blocks rendering`() {
    val delegate = FakeOutput().apply { attachThrows = true }
    val guard = MonitorOutputGuard(delegate, Logger.getLogger("guard-attach")) { _, _, _, _ -> }
    val context = context()

    assertFalse(guard.attach(context))
    guard.render(context, payload())

    assertEquals(0, delegate.renders)
  }

  @Test
  fun `an attach that refuses without throwing also blocks rendering`() {
    val delegate = FakeOutput().apply { attachResult = false }
    val guard = MonitorOutputGuard(delegate, Logger.getLogger("guard-refuse")) { _, _, _, _ -> }
    val context = context()

    assertFalse(guard.attach(context))
    guard.render(context, payload())

    assertEquals(0, delegate.renders)
  }

  @Test
  fun `a flaky renderer logs once instead of once per failure`() {
    val delegate = FakeOutput()
    val logger = Logger.getLogger("guard-flaky")
    val handler = CountingHandler()
    logger.useParentHandlers = false
    logger.addHandler(handler)
    val guard = MonitorOutputGuard(delegate, logger) { _, _, _, _ -> }
    val context = context()

    guard.attach(context)
    repeat(4) {
      delegate.renderFailures = 1
      guard.render(context, payload())
      guard.render(context, payload())
    }

    assertEquals(1, handler.records)
  }

  @Test
  fun `detach clears the trip so the next attach starts clean`() {
    val delegate = FakeOutput().apply { renderFailures = 3 }
    val guard = MonitorOutputGuard(delegate, Logger.getLogger("guard-detach")) { _, _, _, _ -> }
    val context = context()

    guard.attach(context)
    repeat(3) { guard.render(context, payload()) }
    guard.detach(context)
    assertTrue(guard.attach(context))
    guard.render(context, payload())

    assertEquals(4, delegate.renders)
  }
}
