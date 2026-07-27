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
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorToken
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MonitorHudRuntimeConfigTest {
  private val logger = Logger.getLogger("monitor-config-test")

  private fun load(yaml: String, viewSlot: Int = 2): MonitorHudRuntimeConfig {
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    return MonitorHudRuntimeConfig.from(ConfigView(loader.load()), viewSlot, logger)
  }

  private fun empty(viewSlot: Int = 2): MonitorHudRuntimeConfig =
    MonitorHudRuntimeConfig.from(ConfigView(CommentedConfigurationNode.root()), viewSlot, logger)

  @Test
  fun `an empty file yields the shipped defaults`() {
    val config = empty()

    assertEquals(2L, config.updateTicks)
    assertEquals(10, config.actionBar.keepAliveCycles)
    assertEquals(2, config.format.pingMinWidth)
    assertEquals(0.40, config.bossBar.yellowThreshold)
    assertEquals(0.75, config.bossBar.redThreshold)
    assertEquals(1, config.sidebar.slot)
    assertNull(config.bossBar.fixedColor)
  }

  @Test
  fun `only the action bar is on until an operator opts in`() {
    val config = empty()

    assertTrue(config.isEnabled(MonitorOutputKind.ACTIONBAR))
    assertFalse(config.isEnabled(MonitorOutputKind.BOSSBAR))
    assertFalse(config.isEnabled(MonitorOutputKind.SIDEBAR))
    assertFalse(config.isEnabled(MonitorOutputKind.CHAT))
    assertFalse(config.isEnabled(MonitorOutputKind.TABLIST))
  }

  @Test
  fun `an absent per-output keepalive inherits the shared one`() {
    val config = load("update: 2\nbehavior:\n  keepalive-ticks: 40\n")

    assertEquals(20, config.actionBar.keepAliveCycles)
  }

  @Test
  fun `a per-output keepalive overrides the shared one`() {
    val config =
      load(
        "update: 2\nbehavior:\n  keepalive-ticks: 40\noutputs:\n  actionbar:\n    keepalive-ticks: 10\n"
      )

    assertEquals(5, config.actionBar.keepAliveCycles)
  }

  @Test
  fun `a sidebar slot that collides with view is refused`() {
    val config = load("outputs:\n  sidebar:\n    enabled: true\n    slot: 2\n", viewSlot = 2)

    assertFalse(config.sidebar.enabled)
  }

  @Test
  fun `a sidebar on its own slot stays enabled`() {
    val config = load("outputs:\n  sidebar:\n    enabled: true\n    slot: 1\n", viewSlot = 2)

    assertTrue(config.sidebar.enabled)
  }

  @Test
  fun `thresholds in the wrong order are swapped`() {
    val config =
      load("outputs:\n  bossbar:\n    color-thresholds:\n      yellow: 0.9\n      red: 0.2\n")

    assertEquals(0.2, config.bossBar.yellowThreshold)
    assertEquals(0.9, config.bossBar.redThreshold)
  }

  @Test
  fun `a mode listing only unknown tokens falls back`() {
    val config = load("modes:\n  compact: [nonsense, junk]\n")

    assertEquals(
      listOf(MonitorToken.PROB, MonitorToken.TREND, MonitorToken.BUFFER),
      config.tokens(MonitorMode.COMPACT),
    )
  }

  @Test
  fun `unknown tokens are dropped from an otherwise valid mode`() {
    val config = load("modes:\n  compact: [name, junk, prob]\n")

    assertEquals(listOf(MonitorToken.NAME, MonitorToken.PROB), config.tokens(MonitorMode.COMPACT))
  }

  @Test
  fun `a zero reassert interval disables re-claiming`() {
    val config = load("outputs:\n  sidebar:\n    reassert-ticks: 0\n")

    assertEquals(0, config.sidebar.reassertCycles)
  }

  @Test
  fun `a blank flagged template reuses the plain live template`() {
    val config = load("outputs:\n  chat:\n    live:\n      template: \"{prob}\"\n")

    assertEquals("{prob}", config.chat.flaggedTemplate)
  }

  @Test
  fun `sidebar lines are capped`() {
    val many = (1..20).joinToString("\n") { "      - \"line $it\"" }
    val config = load("outputs:\n  sidebar:\n    lines:\n$many\n")

    assertEquals(SIDEBAR_MAX_LINES, config.sidebar.lines.size)
  }
}
