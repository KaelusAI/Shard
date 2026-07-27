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
package ac.shard.monitor.hud.output

import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.ScoreDisplay
import ac.shard.monitor.core.ScoreboardPacketBridge
import ac.shard.monitor.core.ScoreboardSlotRegistry
import ac.shard.monitor.core.SlotLossReason
import ac.shard.monitor.hud.MonitorFrame
import ac.shard.monitor.hud.MonitorHudRuntimeConfig
import ac.shard.monitor.hud.MonitorRenderContext
import ac.shard.monitor.hud.MonitorRenderPayload
import ac.shard.monitor.hud.MonitorSeverity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class SidebarDiffTest {
  private val viewerId = UUID.randomUUID()
  private val targetId = UUID.randomUUID()
  private val bridge = mockk<ScoreboardPacketBridge>(relaxed = true)
  private val registry = ScoreboardSlotRegistry()
  private val output = SidebarOutput(bridge, registry)
  private val viewer = mockk<Player>(relaxed = true)

  private val yaml =
    """
    outputs:
      sidebar:
        enabled: true
        slot: 1
        drop-blank-lines: true
        unavailable-line: "none"
        lines:
          - "A{prob}"
          - "{spacer}"
          - "C"
    """
      .trimIndent()

  private val runtimeConfig: MonitorHudRuntimeConfig =
    YamlConfigurationLoader.builder()
      .source { yaml.reader().buffered() }
      .build()
      .let { loader ->
        MonitorHudRuntimeConfig.from(
          ConfigView(loader.load()),
          2,
          Logger.getLogger("sidebar-diff-test"),
        )
      }

  private fun context(sessionId: Long = 1L): MonitorRenderContext {
    every { viewer.uniqueId } returns viewerId
    every { viewer.isOnline } returns true
    return MonitorRenderContext(
      viewer,
      viewerId,
      sessionId,
      MonitorChatStyle.SUMMARY,
      runtimeConfig,
    )
  }

  private fun payload(
    prob: String = "43",
    spacer: String = "S",
    names: List<String> = listOf("Steve"),
  ): MonitorRenderPayload =
    MonitorRenderPayload(
      names.map { name ->
        MonitorFrame(
          targetId = if (name == "Steve") targetId else UUID.randomUUID(),
          targetName = name,
          headline = "h",
          placeholders = mapOf("prob" to prob, "spacer" to spacer, "name" to name),
          progress = 0f,
          severity = MonitorSeverity.CALM,
          dataPresent = true,
          aiActive = true,
        )
      },
      emptyMap(),
    )

  @Test
  fun `attaching claims the slot and creates the objective`() {
    val context = context()

    assertTrue(output.attach(context))

    verify(exactly = 1) { bridge.createObjective(viewer, any()) }
    assertFalse(registry.isIdle())
  }

  @Test
  fun `the first render writes one entry per line`() {
    val context = context()
    output.attach(context)

    output.render(context, payload())

    verify(exactly = 3) { bridge.updateEntry(viewer, any(), any(), any(), any()) }
  }

  @Test
  fun `an unchanged render writes nothing`() {
    val context = context()
    output.attach(context)
    output.render(context, payload())

    output.render(context, payload())

    verify(exactly = 3) { bridge.updateEntry(viewer, any(), any(), any(), any()) }
  }

  @Test
  fun `one changed line writes exactly one entry`() {
    val context = context()
    output.attach(context)
    output.render(context, payload(prob = "43"))

    output.render(context, payload(prob = "88"))

    verify(exactly = 4) { bridge.updateEntry(viewer, any(), any(), any(), any()) }
    verify(exactly = 1) {
      bridge.updateEntry(
        viewer,
        any(),
        any(),
        3,
        ScoreDisplay(entryDisplay = "A88", scoreText = null),
      )
    }
  }

  @Test
  fun `losing a line removes the tail and rescores the rest`() {
    val context = context()
    output.attach(context)
    output.render(context, payload(spacer = "S"))

    output.render(context, payload(spacer = ""))

    verify(exactly = 1) { bridge.removeEntry(viewer, any(), any()) }
    verify(exactly = 5) { bridge.updateEntry(viewer, any(), any(), any(), any()) }
  }

  @Test
  fun `clearing removes every entry and the objective`() {
    val context = context()
    output.attach(context)
    output.render(context, payload())

    output.clear(context)

    verify(exactly = 3) { bridge.removeEntry(viewer, any(), any()) }
    verify(exactly = 1) { bridge.removeObjective(viewer, any()) }
  }

  @Test
  fun `detaching releases the slot claim`() {
    val context = context()
    output.attach(context)

    output.detach(context)

    assertTrue(registry.isIdle())
  }

  @Test
  fun `a stale detach cannot release a newer session's claim`() {
    val stale = context(sessionId = 1L)
    output.attach(stale)
    val fresh = context(sessionId = 2L)
    output.attach(fresh)

    output.detach(stale)

    assertFalse(registry.isIdle())
  }

  @Test
  fun `an unavailable target collapses to the single unavailable line`() {
    val context = context()
    output.attach(context)

    output.render(
      context,
      MonitorRenderPayload(
        listOf(
          MonitorFrame(
            targetId = targetId,
            targetName = "Steve",
            headline = "h",
            placeholders = emptyMap(),
            progress = 0f,
            severity = MonitorSeverity.CALM,
            dataPresent = false,
            aiActive = false,
          )
        ),
        emptyMap(),
      ),
    )

    verify(exactly = 1) {
      bridge.updateEntry(
        viewer,
        any(),
        any(),
        1,
        ScoreDisplay(entryDisplay = "none", scoreText = null),
      )
    }
  }

  @Test
  fun `losing the objective rebuilds it on the next render`() {
    val context = context()
    output.attach(context)
    output.render(context, payload())
    val claim = registry.claimsFor(viewerId)!!.single()

    claim.onLost.onSlotLost(viewer, SlotLossReason.OBJECTIVE_REMOVED, "other")
    output.render(context, payload())

    verify(exactly = 2) { bridge.createObjective(viewer, any()) }
    verify(exactly = 6) { bridge.updateEntry(viewer, any(), any(), any(), any()) }
  }

  @Test
  fun `a displacement after clear does not resurrect the removed objective`() {
    val context = context()
    output.attach(context)
    output.render(context, payload())
    val claim = registry.claimsFor(viewerId)!!.single()
    output.clear(context)

    claim.onLost.onSlotLost(viewer, SlotLossReason.DISPLACED, "otherPlugin")

    verify(exactly = 1) { bridge.displayObjective(viewer, any(), any()) }
    verify(exactly = 0) {
      bridge.displayObjective(viewer, sidebarObjectiveName(viewerId, 1L), any())
    }
  }

  @Test
  fun `a displacement while the objective is live re-claims the slot`() {
    val context = context()
    output.attach(context)
    output.render(context, payload())
    val claim = registry.claimsFor(viewerId)!!.single()

    claim.onLost.onSlotLost(viewer, SlotLossReason.DISPLACED, "otherPlugin")

    verify(exactly = 1) { bridge.displayObjective(viewer, sidebarObjectiveName(viewerId, 1L), 1) }
  }

  @Test
  fun `a displacement raised synchronously while clearing does not resurrect the objective`() {
    val context = context()
    output.attach(context)
    output.render(context, payload())
    val claim = registry.claimsFor(viewerId)!!.single()
    val ours = sidebarObjectiveName(viewerId, 1L)
    val displayed = mutableListOf<String>()
    every { bridge.displayObjective(viewer, any(), any()) } answers
      {
        val name = secondArg<String>()
        displayed.add(name)
        if (name != ours) {
          claim.onLost.onSlotLost(viewer, SlotLossReason.DISPLACED, name)
        }
      }
    claim.onLost.onSlotLost(viewer, SlotLossReason.DISPLACED, "otherPlugin")

    output.clear(context)

    assertEquals(listOf(ours, "otherPlugin"), displayed)
  }

  @Test
  fun `entry keys are derived from the line index`() {
    assertEquals("§0", sidebarEntryKey(0))
    assertEquals("§1", sidebarEntryKey(1))
    assertEquals("§f", sidebarEntryKey(15))
  }

  @Test
  fun `the objective name fits the sixteen character protocol limit`() {
    assertEquals(16, sidebarObjectiveName(viewerId, 1L).length)
    assertEquals(16, sidebarObjectiveName(viewerId, Long.MAX_VALUE).length)
  }

  @Test
  fun `two targets are drawn with a separator between their blocks`() {
    val config =
      MonitorHudRuntimeConfig.from(
          ConfigView(
            YamlConfigurationLoader.builder()
              .source {
                """
                outputs:
                  sidebar:
                    enabled: true
                    target-separator: "--"
                    lines:
                      - "N{name}"
                """
                  .trimIndent()
                  .reader()
                  .buffered()
              }
              .build()
              .load()
          ),
          2,
          Logger.getLogger("sidebar-separator-test"),
        )
        .sidebar

    val lines = buildSidebarLines(payload(names = listOf("Steve", "Alex")), config)

    assertEquals(listOf("NSteve", "--", "NAlex"), lines)
  }

  @Test
  fun `each session gets its own objective name`() {
    assertNotEquals(sidebarObjectiveName(viewerId, 1L), sidebarObjectiveName(viewerId, 2L))
  }

  @Test
  fun `a restart removes the objective the previous session left behind`() {
    val first = context(sessionId = 1L)
    output.attach(first)
    output.render(first, payload())

    output.attach(context(sessionId = 2L))

    verify(exactly = 1) { bridge.removeObjective(viewer, sidebarObjectiveName(viewerId, 1L)) }
  }
}
