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
package ac.shard.monitor.core

import ac.shard.config.ConfigManager
import ac.shard.config.ConfigView
import ac.shard.database.DatabaseManager
import ac.shard.database.ViolationDatabase
import ac.shard.scheduler.SchedulerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MonitorSettingsServiceTest {
  private val uuid = UUID.randomUUID()
  private val database = mockk<ViolationDatabase>(relaxed = true)
  private val databaseManager = mockk<DatabaseManager>()
  private val configManager = mockk<ConfigManager>()
  private val scheduler = mockk<SchedulerService>()
  private val asyncTasks = mutableListOf<Runnable>()

  private val stored =
    MonitorSettings(
      mode = MonitorMode.FULL,
      theme = MonitorTheme.VIVID,
      showPing = false,
      showDmg = false,
      showTrend = false,
      showName = MonitorNameMode.ALWAYS,
      outputs = setOf(MonitorOutputKind.SIDEBAR),
      chatStyle = MonitorChatStyle.LIVE,
    )

  private fun service(yaml: String = "", deferAsync: Boolean = false): MonitorSettingsService {
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    every { configManager.monitorConfig } returns ConfigView(loader.load())
    every { databaseManager.database } returns database
    every { scheduler.runAsync(any()) } answers
      {
        val task = firstArg<Runnable>()
        if (deferAsync) asyncTasks.add(task) else task.run()
        mockk(relaxed = true)
      }
    every { scheduler.runSync(any<Player>(), any()) } answers
      {
        secondArg<Runnable>().run()
        mockk(relaxed = true)
      }
    return MonitorSettingsService(configManager, databaseManager, scheduler)
  }

  private fun viewer(id: UUID = uuid): Player {
    val player = mockk<Player>(relaxed = true)
    every { player.uniqueId } returns id
    return player
  }

  private fun drainAsync() {
    val pending = asyncTasks.toList()
    asyncTasks.clear()
    pending.forEach { it.run() }
  }

  @Test
  fun `an unknown player reads the configured defaults without touching the database`() {
    val service = service("defaults:\n  theme: minimal\n")

    val settings = service.getSettings(uuid)

    assertEquals(MonitorTheme.MINIMAL, settings.theme)
    verify(exactly = 0) { database.loadMonitorSettings(any()) }
  }

  @Test
  fun `prewarm publishes the stored row`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service()

    service.prewarm(uuid)

    assertEquals(stored, service.getSettings(uuid))
  }

  @Test
  fun `a render read never blocks on the database even before prewarm lands`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service(deferAsync = true)
    service.prewarm(uuid)

    val duringLoad = service.getSettings(uuid)
    drainAsync()

    assertEquals(service.defaults(), duringLoad)
    assertEquals(stored, service.getSettings(uuid))
  }

  @Test
  fun `a quit before the load lands does not resurrect the entry`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service(deferAsync = true)
    service.prewarm(uuid)

    service.evict(uuid)
    drainAsync()

    assertEquals(service.defaults(), service.getSettings(uuid))
  }

  @Test
  fun `a rejoin during an in-flight load does not inherit the old snapshot`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service(deferAsync = true)
    service.prewarm(uuid)
    service.evict(uuid)

    every { database.loadMonitorSettings(uuid) } returns null
    service.prewarm(uuid)
    drainAsync()

    assertEquals(service.defaults(), service.getSettings(uuid))
  }

  @Test
  fun `mutating a loaded entry applies and persists`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service()
    service.prewarm(uuid)
    var applied: MonitorSettings? = null

    service.mutate(viewer(), { it.copy(theme = MonitorTheme.CALM) }) { applied = it }

    assertEquals(MonitorTheme.CALM, applied?.theme)
    assertEquals(MonitorTheme.CALM, service.getSettings(uuid).theme)
    verify { database.saveMonitorSettings(uuid, match { it.theme == MonitorTheme.CALM }) }
  }

  @Test
  fun `mutating an unloaded entry loads it first instead of writing over the stored row`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service()
    var applied: MonitorSettings? = null

    service.mutate(viewer(), { it.copy(theme = MonitorTheme.CALM) }) { applied = it }

    assertEquals(MonitorTheme.CALM, applied?.theme)
    assertEquals(MonitorMode.FULL, applied?.mode)
    assertEquals(setOf(MonitorOutputKind.SIDEBAR), applied?.outputs)
  }

  @Test
  fun `two rapid changes both reach the database in order`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service()
    service.prewarm(uuid)
    val player = viewer()

    service.mutate(player, { it.copy(theme = MonitorTheme.CALM) }) {}
    service.mutate(player, { it.copy(theme = MonitorTheme.MINIMAL) }) {}

    verify { database.saveMonitorSettings(uuid, match { it.theme == MonitorTheme.CALM }) }
    verify { database.saveMonitorSettings(uuid, match { it.theme == MonitorTheme.MINIMAL }) }
    assertEquals(MonitorTheme.MINIMAL, service.getSettings(uuid).theme)
  }

  @Test
  fun `per-player storage off never reads or writes the database`() {
    val service = service("storage:\n  per-player: false\n")
    service.prewarm(uuid)

    service.mutate(viewer(), { it.copy(theme = MonitorTheme.MINIMAL) }) {}

    assertEquals(MonitorTheme.MINIMAL, service.getSettings(uuid).theme)
    verify(exactly = 0) { database.loadMonitorSettings(any()) }
    verify(exactly = 0) { database.saveMonitorSettings(any(), any()) }
  }

  @Test
  fun `per-player storage off forgets the session on quit`() {
    val service = service("storage:\n  per-player: false\n")
    service.prewarm(uuid)
    service.mutate(viewer(), { it.copy(theme = MonitorTheme.MINIMAL) }) {}

    service.evict(uuid)

    assertEquals(service.defaults(), service.getSettings(uuid))
  }

  @Test
  fun `prewarm-on-join off leaves the row unread until something needs it`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service("storage:\n  prewarm-on-join: false\n")

    service.prewarm(uuid)

    verify(exactly = 0) { database.loadMonitorSettings(any()) }
    assertEquals(service.defaults(), service.getSettings(uuid))
  }

  @Test
  fun `reload picks up new defaults`() {
    val service = service("defaults:\n  theme: calm\n")
    assertEquals(MonitorTheme.CALM, service.defaults().theme)

    val loader =
      YamlConfigurationLoader.builder()
        .source { "defaults:\n  theme: vivid\n".reader().buffered() }
        .build()
    every { configManager.monitorConfig } returns ConfigView(loader.load())
    service.reload()

    assertEquals(MonitorTheme.VIVID, service.defaults().theme)
  }

  @Test
  fun `reload keeps cached rows when the storage mode did not change`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service("defaults:\n  theme: calm\n")
    service.prewarm(uuid)

    service.reload()

    assertEquals(stored, service.getSettings(uuid))
  }

  @Test
  fun `reload drops cached rows when the storage mode flips`() {
    every { database.loadMonitorSettings(uuid) } returns stored
    val service = service("storage:\n  per-player: true\n")
    service.prewarm(uuid)
    assertTrue(service.getSettings(uuid) == stored)

    val loader =
      YamlConfigurationLoader.builder()
        .source { "storage:\n  per-player: false\n".reader().buffered() }
        .build()
    every { configManager.monitorConfig } returns ConfigView(loader.load())
    service.reload()

    assertFalse(service.getSettings(uuid) == stored)
  }
}
