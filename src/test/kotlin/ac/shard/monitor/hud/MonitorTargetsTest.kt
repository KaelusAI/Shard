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

class MonitorTargetsTest {
  private val viewerId = UUID.randomUUID()
  private val configManager = mockk<ConfigManager>()
  private val localeManager = mockk<LocaleManager>()
  private val settingsService = mockk<MonitorSettingsService>()
  private val scheduler = mockk<SchedulerService>()
  private val index = MonitorTargetIndex()

  private class RoomyOutput(
    private val room: Int,
    override val kind: MonitorOutputKind = MonitorOutputKind.ACTIONBAR,
  ) : MonitorOutput {

    override val capabilities =
      MonitorOutputCapabilities(
        maxTargets = room,
        claimsClientSlot = false,
        eventDriven = false,
        requiresClear = true,
      )

    override fun isAvailable(): Boolean = true

    override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
      MonitorOutputPolicy(0, 0)

    override fun attach(context: MonitorRenderContext): Boolean = true

    override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) = Unit

    override fun clear(context: MonitorRenderContext) = Unit

    override fun detach(context: MonitorRenderContext) = Unit
  }

  private fun hud(
    room: Int = 4,
    yaml: String = "",
    outputs: List<MonitorOutput> = listOf(RoomyOutput(room)),
    stored: MonitorOutputKind = MonitorOutputKind.ACTIONBAR,
    storedOutputs: Set<MonitorOutputKind> = setOf(stored),
  ): MonitorHudService {
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    every { configManager.monitorConfig } returns ConfigView(loader.load())
    every { localeManager.getRawMessage(any()) } returns "text"
    every { settingsService.getSettings(any()) } returns settings(storedOutputs)
    every { settingsService.defaults() } returns settings(storedOutputs)
    every { scheduler.runTimer(any<Player>(), any(), any(), any()) } returns mockk(relaxed = true)
    every { scheduler.runSync(any<Player>(), any()) } answers
      {
        secondArg<Runnable>().run()
        mockk(relaxed = true)
      }
    return MonitorHudService(
      scheduler,
      settingsService,
      mockk<MonitorSampler>(relaxed = true),
      MonitorFrameBuilder(),
      MonitorOutputRegistry(outputs),
      index,
      configManager,
      localeManager,
      Logger.getLogger("targets-test"),
    )
  }

  private fun settings(outputs: Set<MonitorOutputKind> = setOf(MonitorOutputKind.ACTIONBAR)) =
    MonitorSettings(
      mode = MonitorMode.COMPACT,
      theme = MonitorTheme.CALM,
      showPing = true,
      showDmg = true,
      showTrend = true,
      showName = MonitorNameMode.AUTO,
      outputs = outputs,
    )

  private fun player(name: String, id: UUID = UUID.randomUUID()): Player {
    val player = mockk<Player>(relaxed = true)
    every { player.uniqueId } returns id
    every { player.name } returns name
    every { player.isOnline } returns true
    return player
  }

  private fun targetsService(hud: MonitorHudService) =
    MonitorTargetsService(hud, index, localeManager)

  @Test
  fun `an index maps every target of a viewer back to it`() {
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()

    index.set(viewerId, listOf(first, second))

    assertEquals(setOf(viewerId), index.viewersOf(first))
    assertEquals(setOf(viewerId), index.viewersOf(second))
  }

  @Test
  fun `re-indexing a viewer forgets the targets it dropped`() {
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    index.set(viewerId, listOf(first, second))

    index.set(viewerId, listOf(second))

    assertEquals(emptySet(), index.viewersOf(first))
    assertEquals(setOf(viewerId), index.viewersOf(second))
  }

  @Test
  fun `clearing one viewer leaves the others watching`() {
    val other = UUID.randomUUID()
    val target = UUID.randomUUID()
    index.set(viewerId, listOf(target))
    index.set(other, listOf(target))

    index.clear(viewerId)

    assertEquals(setOf(other), index.viewersOf(target))
  }

  @Test
  fun `adding a second target extends the session`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    val first = player("Steve")
    hud.start(viewer, first)
    val second = player("Alex")

    assertEquals(TargetChange.APPLIED, targets.add(viewer, second))

    assertEquals(listOf("Steve", "Alex"), targets.names(viewerId))
    assertEquals(setOf(viewerId), index.viewersOf(second.uniqueId))
  }

  @Test
  fun `adding the same target twice changes nothing`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    val steve = player("Steve")
    hud.start(viewer, steve)

    assertEquals(TargetChange.ALREADY_WATCHED, targets.add(viewer, steve))

    assertEquals(1, targets.size(viewerId))
  }

  @Test
  fun `adding past the output's capacity is refused`() {
    val hud = hud(room = 2)
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))
    targets.add(viewer, player("Alex"))

    assertEquals(TargetChange.LIMIT_REACHED, targets.add(viewer, player("Notch")))

    assertEquals(2, targets.size(viewerId))
  }

  @Test
  fun `adding without a session is reported`() {
    val hud = hud()
    val targets = targetsService(hud)

    assertEquals(TargetChange.NO_SESSION, targets.add(player("Admin", viewerId), player("Steve")))
  }

  @Test
  fun `removing one of several keeps the session alive`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    val steve = player("Steve")
    hud.start(viewer, steve)
    targets.add(viewer, player("Alex"))

    assertEquals(TargetChange.APPLIED, targets.remove(viewer, "Steve"))

    assertNotNull(hud.session(viewerId))
    assertEquals(listOf("Alex"), targets.names(viewerId))
    assertEquals(emptySet(), index.viewersOf(steve.uniqueId))
  }

  @Test
  fun `removing the last target ends the session`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))

    assertEquals(TargetChange.APPLIED, targets.remove(viewer, "Steve"))

    assertNull(hud.session(viewerId))
  }

  @Test
  fun `removing a name nobody is watching is reported`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))

    assertEquals(TargetChange.NOT_WATCHED, targets.remove(viewer, "Notch"))
  }

  @Test
  fun `removing is case-insensitive`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))
    targets.add(viewer, player("Alex"))

    assertEquals(TargetChange.APPLIED, targets.remove(viewer, "sTeVe"))
  }

  @Test
  fun `clearing reports how many were being watched`() {
    val hud = hud()
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))
    targets.add(viewer, player("Alex"))

    assertEquals(2, targets.clear(viewer))

    assertNull(hud.session(viewerId))
  }

  @Test
  fun `capacity follows the roomiest output, not the narrowest`() {
    val hud =
      hud(
        yaml = "outputs:\n  sidebar:\n    enabled: true\n",
        outputs =
          listOf(
            RoomyOutput(1, MonitorOutputKind.ACTIONBAR),
            RoomyOutput(4, MonitorOutputKind.SIDEBAR),
          ),
        storedOutputs = setOf(MonitorOutputKind.ACTIONBAR, MonitorOutputKind.SIDEBAR),
      )
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))

    assertEquals(4, targets.capacity(viewerId))
    assertEquals(TargetChange.APPLIED, targets.add(viewer, player("Alex")))
  }

  @Test
  fun `capacity is the smaller of the declared and the configured limit`() {
    val hud =
      hud(
        yaml = "outputs:\n  bossbar:\n    enabled: true\n    max-bars: 2\n",
        outputs = listOf(RoomyOutput(6, MonitorOutputKind.BOSSBAR)),
        stored = MonitorOutputKind.BOSSBAR,
      )
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))

    assertEquals(2, targets.capacity(viewerId))
  }

  @Test
  fun `the configured boss bar limit also bounds what can be added`() {
    val hud =
      hud(
        yaml = "outputs:\n  bossbar:\n    enabled: true\n    max-bars: 2\n",
        outputs = listOf(RoomyOutput(6, MonitorOutputKind.BOSSBAR)),
        stored = MonitorOutputKind.BOSSBAR,
      )
    val targets = targetsService(hud)
    val viewer = player("Admin", viewerId)
    hud.start(viewer, player("Steve"))
    targets.add(viewer, player("Alex"))

    assertEquals(TargetChange.LIMIT_REACHED, targets.add(viewer, player("Notch")))
  }
}
