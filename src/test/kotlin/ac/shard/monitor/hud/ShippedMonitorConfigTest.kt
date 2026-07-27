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
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ShippedMonitorConfigTest {
  private val shipped: MonitorHudRuntimeConfig =
    MonitorHudRuntimeConfig.from(
      ConfigView(
        YamlConfigurationLoader.builder()
          .file(
            File(
              this::class.java.classLoader.getResource("monitor.yml")?.toURI()
                ?: error("bundled monitor.yml is missing from the test classpath")
            )
          )
          .build()
          .load()
      ),
      2,
      Logger.getLogger("shipped-monitor-config"),
    )

  private fun frame(showName: MonitorNameMode = MonitorNameMode.ALWAYS): MonitorFrame =
    MonitorFrameBuilder()
      .build(
        MonitorFrameRequest(
          sample =
            MonitorSample(
              targetId = UUID.randomUUID(),
              targetName = "Steve",
              dataPresent = true,
              aiActive = true,
              probability = 0.43,
              buffer = 2.5,
              rawPing = 57,
              damageMultiplier = 1.0,
              prob90 = 0,
            ),
          settings =
            MonitorSettings(
              mode = MonitorMode.COMPACT,
              theme = MonitorTheme.CALM,
              showPing = true,
              showDmg = true,
              showTrend = true,
              showName = showName,
            ),
          pingValue = 57,
          trend = 0.0,
          selfView = false,
          unavailableHeadline = "no data",
        ),
        shipped,
      )

  @Test
  fun `the shipped compact mode reproduces the current action bar`() {
    assertEquals(
      "<gray>@Steve</gray><dark_gray> • </dark_gray>" +
        "<bold><white>43%</white></bold><dark_gray> • </dark_gray>" +
        "<color:#86EFAC>+0.00</color><dark_gray> • </dark_gray>" +
        "<color:#FBBF24>◆ 2.50</color>",
      frame().headline,
    )
  }

  @Test
  fun `the shipped file drives the timing the monitor already used`() {
    assertEquals(2L, shipped.updateTicks)
    assertEquals(10, shipped.actionBar.keepAliveCycles)
  }

  @Test
  fun `the shipped file offers every output`() {
    assertEquals(
      MonitorOutputKind.entries,
      MonitorOutputKind.entries.filter { shipped.isEnabled(it) },
    )
  }

  @Test
  fun `the shipped sidebar does not collide with the shipped view slot`() {
    assertEquals(1, shipped.sidebar.slot)
    assertTrue(shipped.sidebar.enabled)
  }

  @Test
  fun `the shipped action bar keepalive inherits the shared setting`() {
    assertEquals(10, shipped.actionBar.keepAliveCycles)
  }
}
