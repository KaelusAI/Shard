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
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.hud.MonitorFrame
import ac.shard.monitor.hud.MonitorHudRuntimeConfig
import ac.shard.monitor.hud.MonitorOutputRegistry
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
import kotlin.test.assertNull
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class SimpleOutputsTest {
  private val viewerId = UUID.randomUUID()
  private val audience = mockk<Audience>(relaxed = true)
  private val adventure = mockk<BukkitAudiences>()
  private val viewer = mockk<Player>(relaxed = true)
  private val cache = ComponentCache()

  private fun config(yaml: String? = null): MonitorHudRuntimeConfig {
    val node =
      if (yaml == null) {
        CommentedConfigurationNode.root()
      } else {
        YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build().load()
      }
    return MonitorHudRuntimeConfig.from(ConfigView(node), 2, Logger.getLogger("simple-outputs"))
  }

  private fun context(
    runtimeConfig: MonitorHudRuntimeConfig = config(),
    online: Boolean = true,
  ): MonitorRenderContext {
    every { viewer.uniqueId } returns viewerId
    every { viewer.isOnline } returns online
    every { adventure.player(viewer) } returns audience
    return MonitorRenderContext(viewer, viewerId, 1L, MonitorChatStyle.SUMMARY, runtimeConfig)
  }

  private fun payload(headline: String = "hello"): MonitorRenderPayload =
    MonitorRenderPayload(
      listOf(
        MonitorFrame(
          targetId = UUID.randomUUID(),
          targetName = "Steve",
          headline = headline,
          placeholders = mapOf("headline" to headline, "name" to "Steve"),
          progress = 0f,
          severity = MonitorSeverity.CALM,
          dataPresent = true,
          aiActive = true,
        )
      ),
      emptyMap(),
    )

  @Test
  fun `the action bar sends the headline it was given`() {
    val output = ActionBarOutput(adventure, cache)
    val context = context()

    output.render(context, payload("<red>hi</red>"))

    verify { audience.sendActionBar(MiniMessage.miniMessage().deserialize("<red>hi</red>")) }
  }

  @Test
  fun `the action bar blanks itself on clear`() {
    val output = ActionBarOutput(adventure, cache)

    output.clear(context())

    verify { audience.sendActionBar(Component.empty()) }
  }

  @Test
  fun `an offline viewer receives nothing`() {
    val output = ActionBarOutput(adventure, cache)
    val context = context(online = false)

    output.render(context, payload())
    output.clear(context)

    verify(exactly = 0) { audience.sendActionBar(any<Component>()) }
  }

  @Test
  fun `the tab list fills header and footer from the frame`() {
    val output = TabListOutput(adventure, cache)
    val runtimeConfig =
      config("outputs:\n  tablist:\n    header: \"H{name}\"\n    footer: \"F{headline}\"\n")

    output.render(context(runtimeConfig), payload("done"))

    verify {
      audience.sendPlayerListHeaderAndFooter(
        MiniMessage.miniMessage().deserialize("HSteve"),
        MiniMessage.miniMessage().deserialize("Fdone"),
      )
    }
  }

  @Test
  fun `the tab list blanks both halves on clear`() {
    val output = TabListOutput(adventure, cache)

    output.clear(context())

    verify { audience.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty()) }
  }

  @Test
  fun `the registry resolves an output by kind`() {
    val actionBar = ActionBarOutput(adventure, cache)
    val registry = MonitorOutputRegistry(listOf(actionBar))

    assertEquals(actionBar, registry.output(MonitorOutputKind.ACTIONBAR))
    assertNull(registry.output(MonitorOutputKind.SIDEBAR))
  }

  @Test
  fun `the registry reports the capacity an output declares`() {
    val registry = MonitorOutputRegistry(listOf(ActionBarOutput(adventure, cache)))

    assertEquals(1, registry.capacity(MonitorOutputKind.ACTIONBAR))
    assertEquals(1, registry.capacity(MonitorOutputKind.CHAT))
  }

  @Test
  fun `the registry lists only outputs that are both present and enabled`() {
    val registry =
      MonitorOutputRegistry(
        listOf(ActionBarOutput(adventure, cache), TabListOutput(adventure, cache))
      )

    assertEquals(listOf(MonitorOutputKind.ACTIONBAR), registry.available(config()))
    assertEquals(
      listOf(MonitorOutputKind.ACTIONBAR, MonitorOutputKind.TABLIST),
      registry.available(config("outputs:\n  tablist:\n    enabled: true\n")),
    )
  }
}
