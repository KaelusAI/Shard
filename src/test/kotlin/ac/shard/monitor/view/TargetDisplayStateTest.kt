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
package ac.shard.monitor.view

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.bukkit.entity.Player

class TargetDisplayStateTest {
  private val viewer = mockk<Player>(relaxed = true)
  private val target =
    mockk<Player>(relaxed = true).also {
      every { it.entityId } returns 42
      every { it.passengers } returns emptyList()
    }
  private val bridge =
    mockk<ViewDisplayPacketBridge>(relaxed = true).also { every { it.nextId() } returns -7 }

  private fun tag(text: String) = RenderedTag("", "", "", 0, text)

  private fun TargetTeamState.show(text: String, known: Boolean = true) =
    updateDisplay(viewer, target, known, tag(text), DEFAULT_DISPLAY_STYLE, 50, bridge)

  @Test
  fun `the display is spawned once, mounted on the target and then only retexted`() {
    val state = TargetTeamState("slv_a")
    state.show("40%")
    state.show("40%")
    state.show("41%")

    verify(exactly = 1) { bridge.spawn(viewer, -7, target, DEFAULT_DISPLAY_STYLE, "40%") }
    verify(exactly = 1) { bridge.mount(viewer, 42, intArrayOf(-7)) }
    verify(exactly = 1) { bridge.text(viewer, -7, "41%") }
  }

  @Test
  fun `a target the client does not know loses its display and gets it back later`() {
    val state = TargetTeamState("slv_a")
    state.show("40%")
    state.show("40%", known = false)
    state.show("40%")

    verify(exactly = 1) { bridge.destroy(viewer, -7) }
    verify(exactly = 2) { bridge.spawn(viewer, -7, target, any(), any()) }
  }

  @Test
  fun `a server passenger update keeps the display on board`() {
    val state = TargetTeamState("slv_a")
    assertNull(state.passengersWithDisplay(intArrayOf(5)))
    state.show("40%")
    assertContentEquals(intArrayOf(5, -7), state.passengersWithDisplay(intArrayOf(5)))
    assertNull(state.passengersWithDisplay(intArrayOf(5, -7)))
  }

  @Test
  fun `a respawned viewer gets the display spawned again`() {
    val state = TargetTeamState("slv_a")
    state.show("40%")
    state.forgetDisplay()
    state.show("40%")

    verify(exactly = 2) { bridge.spawn(viewer, -7, target, any(), any()) }
  }

  @Test
  fun `the placement and background parse from the config words`() {
    assertEquals(ViewPlacement.TEXT_DISPLAY, parseViewPlacement("text_display"))
    assertNull(parseBackground("default"))
    assertEquals(0, parseBackground("none"))
    assertEquals(0x40000000, parseBackground("#40000000"))
    assertEquals(-1, parseBackground("#FFFFFFFF"))
  }
}
