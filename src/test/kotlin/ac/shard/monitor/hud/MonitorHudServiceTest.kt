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

import ac.shard.config.ConfigManager
import ac.shard.config.ConfigView
import ac.shard.config.LocaleManager
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.core.MonitorTheme
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Message
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.bukkit.entity.Player
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MonitorHudServiceTest {
  private val viewerId = UUID.randomUUID()
  private val targetId = UUID.randomUUID()
  private val configManager = mockk<ConfigManager>()
  private val localeManager = mockk<LocaleManager>()
  private val settingsService = mockk<MonitorSettingsService>()
  private val sampler = mockk<MonitorSampler>(relaxed = true)
  private val scheduler = mockk<SchedulerService>()
  private val index = MonitorTargetIndex()

  private class FakeOutput(override val kind: MonitorOutputKind, val available: Boolean = true) :
    MonitorOutput {
    var attaches = 0
    var detaches = 0
    var clears = 0
    var attachResult = true

    override val capabilities =
      MonitorOutputCapabilities(
        maxTargets = 1,
        claimsClientSlot = false,
        eventDriven = false,
        requiresClear = true,
      )

    override fun isAvailable(): Boolean = available

    override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
      MonitorOutputPolicy(0, 0)

    override fun attach(context: MonitorRenderContext): Boolean {
      attaches++
      return attachResult
    }

    override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) = Unit

    override fun clear(context: MonitorRenderContext) {
      clears++
    }

    override fun detach(context: MonitorRenderContext) {
      detaches++
    }
  }

  private val actionBar = FakeOutput(MonitorOutputKind.ACTIONBAR)
  private val sidebar = FakeOutput(MonitorOutputKind.SIDEBAR)

  private fun service(
    yaml: String = "",
    storedOutput: MonitorOutputKind = MonitorOutputKind.ACTIONBAR,
    storedOutputs: Set<MonitorOutputKind> = setOf(storedOutput),
    outputs: List<MonitorOutput> = listOf(actionBar, sidebar),
  ): MonitorHudService {
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    every { configManager.monitorConfig } returns ConfigView(loader.load())
    every { localeManager.getRawMessage(any()) } returns "text"
    every { settingsService.getSettings(any()) } returns settings(storedOutputs)
    every { settingsService.defaults() } returns settings(setOf(MonitorOutputKind.ACTIONBAR))
    every { scheduler.runTimer(any<Player>(), any(), any(), any()) } returns mockk(relaxed = true)
    every { scheduler.runSync(any<Player>(), any()) } answers
      {
        secondArg<Runnable>().run()
        mockk(relaxed = true)
      }
    return MonitorHudService(
      scheduler,
      settingsService,
      sampler,
      MonitorFrameBuilder(),
      MonitorOutputRegistry(outputs),
      index,
      configManager,
      localeManager,
      Logger.getLogger("hud-service-test"),
    )
  }

  private fun settings(outputs: Set<MonitorOutputKind>) =
    MonitorSettings(
      mode = MonitorMode.COMPACT,
      theme = MonitorTheme.CALM,
      showPing = true,
      showDmg = true,
      showTrend = true,
      showName = MonitorNameMode.AUTO,
      outputs = outputs,
    )

  private fun player(id: UUID, name: String): Player {
    val player = mockk<Player>(relaxed = true)
    every { player.uniqueId } returns id
    every { player.name } returns name
    every { player.isOnline } returns true
    return player
  }

  @Test
  fun `starting attaches the output and indexes the target`() {
    val hud = service()
    val viewer = player(viewerId, "Admin")

    assertEquals(StartResult.STARTED, hud.start(viewer, player(targetId, "Steve")))

    assertEquals(1, actionBar.attaches)
    assertEquals(setOf(viewerId), index.viewersOf(targetId))
    assertNotNull(hud.session(viewerId))
  }

  @Test
  fun `a disabled output falls back to the action bar`() {
    val hud = service(storedOutput = MonitorOutputKind.SIDEBAR)
    val viewer = player(viewerId, "Admin")

    hud.start(viewer, player(targetId, "Steve"))

    assertEquals(MonitorOutputKind.ACTIONBAR, hud.session(viewerId)?.outputs?.firstOrNull()?.kind)
    assertEquals(0, sidebar.attaches)
  }

  @Test
  fun `an enabled output is used as chosen`() {
    val hud =
      service(
        yaml = "outputs:\n  sidebar:\n    enabled: true\n    slot: 1\n",
        storedOutput = MonitorOutputKind.SIDEBAR,
      )
    val viewer = player(viewerId, "Admin")

    hud.start(viewer, player(targetId, "Steve"))

    assertEquals(MonitorOutputKind.SIDEBAR, hud.session(viewerId)?.outputs?.firstOrNull()?.kind)
  }

  @Test
  fun `a session is refused when nothing can render`() {
    val hud = service(outputs = emptyList())
    val viewer = player(viewerId, "Admin")

    assertEquals(StartResult.NO_OUTPUT, hud.start(viewer, player(targetId, "Steve")))

    assertNull(hud.session(viewerId))
  }

  @Test
  fun `a failing attach refuses the session and releases what it allocated`() {
    actionBar.attachResult = false
    val hud = service()
    val viewer = player(viewerId, "Admin")

    assertEquals(StartResult.NO_OUTPUT, hud.start(viewer, player(targetId, "Steve")))

    assertEquals(1, actionBar.detaches)
    assertNull(hud.session(viewerId))
  }

  @Test
  fun `starting again replaces the previous session`() {
    val hud = service()
    val viewer = player(viewerId, "Admin")
    val other = UUID.randomUUID()
    hud.start(viewer, player(targetId, "Steve"))

    hud.start(viewer, player(other, "Alex"))

    assertEquals(emptySet(), index.viewersOf(targetId))
    assertEquals(setOf(viewerId), index.viewersOf(other))
    assertEquals(1, actionBar.clears)
  }

  @Test
  fun `stopping tears the output down and clears the index`() {
    val hud = service()
    val viewer = player(viewerId, "Admin")
    hud.start(viewer, player(targetId, "Steve"))

    hud.stop(viewerId, viewer)

    assertNull(hud.session(viewerId))
    assertEquals(emptySet(), index.viewersOf(targetId))
    assertEquals(1, actionBar.clears)
    assertEquals(1, actionBar.detaches)
  }

  @Test
  fun `every viewer of one target is indexed`() {
    val hud = service()
    val second = UUID.randomUUID()
    val target = player(targetId, "Steve")
    hud.start(player(viewerId, "Admin"), target)
    hud.start(player(second, "Mod"), target)

    assertEquals(setOf(viewerId, second), index.viewersOf(targetId))
  }

  @Test
  fun `stopAll ends every session`() {
    val hud = service()
    val second = UUID.randomUUID()
    val target = player(targetId, "Steve")
    hud.start(player(viewerId, "Admin"), target)
    hud.start(player(second, "Mod"), target)

    hud.stopAll()

    assertNull(hud.session(viewerId))
    assertNull(hud.session(second))
  }

  @Test
  fun `the only output failing ends the session`() {
    val hud =
      service(
        yaml = "outputs:\n  sidebar:\n    enabled: true\n    slot: 1\n",
        storedOutput = MonitorOutputKind.SIDEBAR,
      )
    val viewer = player(viewerId, "Admin")
    hud.start(viewer, player(targetId, "Steve"))

    hud.onOutputFailed(viewerId, MonitorOutputKind.SIDEBAR, "render", RuntimeException("boom"))

    assertNull(hud.session(viewerId))
    assertEquals(1, sidebar.detaches)
  }

  @Test
  fun `one failed output leaves the rest of the set drawing`() {
    val hud =
      service(
        yaml = "outputs:\n  sidebar:\n    enabled: true\n    slot: 1\n",
        storedOutputs = setOf(MonitorOutputKind.ACTIONBAR, MonitorOutputKind.SIDEBAR),
      )
    val viewer = player(viewerId, "Admin")
    hud.start(viewer, player(targetId, "Steve"))
    assertEquals(2, hud.session(viewerId)?.outputs?.size)

    hud.onOutputFailed(viewerId, MonitorOutputKind.SIDEBAR, "render", RuntimeException("boom"))

    assertEquals(
      listOf(MonitorOutputKind.ACTIONBAR),
      hud.session(viewerId)?.outputs?.map { it.kind },
    )
  }

  @Test
  fun `two chosen outputs both attach`() {
    val hud =
      service(
        yaml = "outputs:\n  sidebar:\n    enabled: true\n    slot: 1\n",
        storedOutputs = setOf(MonitorOutputKind.ACTIONBAR, MonitorOutputKind.SIDEBAR),
      )

    hud.start(player(viewerId, "Admin"), player(targetId, "Steve"))

    assertEquals(1, actionBar.attaches)
    assertEquals(1, sidebar.attaches)
  }

  @Test
  fun `a failing action bar ends the session because there is no floor left`() {
    val hud = service()
    val viewer = player(viewerId, "Admin")
    hud.start(viewer, player(targetId, "Steve"))

    hud.onOutputFailed(viewerId, MonitorOutputKind.ACTIONBAR, "render", RuntimeException("boom"))

    assertNull(hud.session(viewerId))
  }

  @Test
  fun `a server at its session limit refuses a new session`() {
    val hud = service(yaml = "limits:\n  max-sessions: 1\n")
    hud.start(player(viewerId, "Admin"), player(targetId, "Steve"))

    val second = hud.start(player(UUID.randomUUID(), "Mod"), player(targetId, "Steve"))

    assertEquals(StartResult.LIMIT_REACHED, second)
  }

  @Test
  fun `a target at its viewer limit refuses another watcher`() {
    val hud = service(yaml = "limits:\n  max-viewers-per-target: 1\n")
    val target = player(targetId, "Steve")
    hud.start(player(viewerId, "Admin"), target)

    val second = hud.start(player(UUID.randomUUID(), "Mod"), target)

    assertEquals(StartResult.LIMIT_REACHED, second)
  }

  @Test
  fun `a zero limit means unlimited`() {
    val hud = service(yaml = "limits:\n  max-sessions: 0\n  max-viewers-per-target: 0\n")
    val target = player(targetId, "Steve")
    hud.start(player(viewerId, "Admin"), target)

    val second = hud.start(player(UUID.randomUUID(), "Mod"), target)

    assertEquals(StartResult.STARTED, second)
  }

  @Test
  fun `restarting a viewer does not count against the session limit`() {
    val hud = service(yaml = "limits:\n  max-sessions: 1\n")
    val viewer = player(viewerId, "Admin")
    hud.start(viewer, player(targetId, "Steve"))

    val again = hud.start(viewer, player(UUID.randomUUID(), "Alex"))

    assertEquals(StartResult.STARTED, again)
  }

  @Test
  fun `an unavailable text resolves the prefix and the player name`() {
    val locale = mockk<LocaleManager>()
    every { locale.getRawMessage(Message.MONITOR_NO_DATA) } returns "<prefix> gone <player>"
    every { locale.getRawMessage(Message.PREFIX) } returns "[S]"

    assertEquals("[S] gone Steve", rawMessageFor(locale, Message.MONITOR_NO_DATA, "Steve"))
  }
}
