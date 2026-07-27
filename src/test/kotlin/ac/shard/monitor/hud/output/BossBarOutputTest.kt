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
import ac.shard.monitor.core.ComponentCache
import ac.shard.monitor.core.MonitorChatStyle
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
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import org.bukkit.entity.Player
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class BossBarOutputTest {
  private val viewerId = UUID.randomUUID()
  private val audience = mockk<Audience>(relaxed = true)
  private val adventure = mockk<BukkitAudiences>()
  private val viewer = mockk<Player>(relaxed = true)
  private val output = BossBarOutput(adventure, ComponentCache())

  private val yaml =
    """
    outputs:
      bossbar:
        enabled: true
        title: "{prob}"
        max-bars: 2
        overflow-template: "+{count}"
    """
      .trimIndent()

  private val runtimeConfig: MonitorHudRuntimeConfig =
    MonitorHudRuntimeConfig.from(
      ConfigView(
        YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build().load()
      ),
      2,
      Logger.getLogger("bossbar-test"),
    )

  private fun context(sessionId: Long = 1L): MonitorRenderContext {
    every { viewer.uniqueId } returns viewerId
    every { viewer.isOnline } returns true
    every { adventure.player(viewer) } returns audience
    return MonitorRenderContext(
      viewer,
      viewerId,
      sessionId,
      MonitorChatStyle.SUMMARY,
      runtimeConfig,
    )
  }

  private fun frame(prob: String, progress: Float) =
    MonitorFrame(
      targetId = UUID.randomUUID(),
      targetName = "T$prob",
      headline = prob,
      placeholders = mapOf("prob" to prob),
      progress = progress,
      severity = MonitorSeverity.CALM,
      dataPresent = true,
      aiActive = true,
    )

  @Test
  fun `attaching alone shows nothing`() {
    output.attach(context())

    verify(exactly = 0) { audience.showBossBar(any()) }
  }

  @Test
  fun `one target gets one bar, created once`() {
    val context = context()
    val target = frame("10", 0.1f)
    output.attach(context)

    output.render(context, MonitorRenderPayload(listOf(target), emptyMap()))
    output.render(context, MonitorRenderPayload(listOf(target), emptyMap()))

    verify(exactly = 1) { audience.showBossBar(any()) }
  }

  @Test
  fun `the cap keeps the most suspicious targets and adds one overflow bar`() {
    val context = context()
    output.attach(context)

    output.render(
      context,
      MonitorRenderPayload(
        listOf(frame("10", 0.1f), frame("90", 0.9f), frame("50", 0.5f), frame("20", 0.2f)),
        emptyMap(),
      ),
    )

    verify(exactly = 3) { audience.showBossBar(any()) }
  }

  @Test
  fun `dropping back under the cap hides the overflow bar`() {
    val context = context()
    output.attach(context)
    val kept = frame("90", 0.9f)
    output.render(
      context,
      MonitorRenderPayload(listOf(kept, frame("50", 0.5f), frame("10", 0.1f)), emptyMap()),
    )

    output.render(context, MonitorRenderPayload(listOf(kept), emptyMap()))

    verify(exactly = 2) { audience.hideBossBar(any()) }
  }

  @Test
  fun `clearing hides every bar it created`() {
    val context = context()
    output.attach(context)
    output.render(
      context,
      MonitorRenderPayload(listOf(frame("90", 0.9f), frame("10", 0.1f)), emptyMap()),
    )

    output.clear(context)

    verify(exactly = 2) { audience.hideBossBar(any()) }
  }

  @Test
  fun `a session that displaces another hides the bars it inherited`() {
    val first = context(sessionId = 1L)
    output.attach(first)
    output.render(first, MonitorRenderPayload(listOf(frame("90", 0.9f)), emptyMap()))

    output.attach(context(sessionId = 2L))

    verify(exactly = 1) { audience.hideBossBar(any<BossBar>()) }
  }
}
