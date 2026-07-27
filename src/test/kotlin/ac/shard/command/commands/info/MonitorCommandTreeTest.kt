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
package ac.shard.command.commands.info

import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorOutputRegistry
import ac.shard.sender.Sender
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertTrue
import org.incendo.cloud.CommandManager
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.internal.CommandRegistrationHandler

class MonitorCommandTreeTest {
  private class TestManager :
    CommandManager<Sender>(
      ExecutionCoordinator.simpleCoordinator(),
      CommandRegistrationHandler.nullCommandRegistrationHandler(),
    ) {
    override fun hasPermission(sender: Sender, permission: String): Boolean = true
  }

  private fun registerAll(): TestManager {
    val manager = TestManager()
    MonitorCommand(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
      .register(manager)
    MonitorSettingsCommand(
        mockk<MonitorSettingsService>(relaxed = true),
        mockk<MonitorHudService>(relaxed = true),
        mockk<MonitorOutputRegistry>(relaxed = true),
        mockk(relaxed = true),
      )
      .register(manager)
    MonitorInfoCommand(
        mockk<MonitorSettingsService>(relaxed = true),
        mockk<MonitorHudService>(relaxed = true),
        mockk<MonitorOutputRegistry>(relaxed = true),
      )
      .register(manager)
    return manager
  }

  private fun chains(manager: TestManager): List<String> =
    manager.commands().map { command ->
      command.components().joinToString(" ") { component -> component.name() }
    }

  @Test
  fun `every monitor command registers without a duplicate chain`() {
    val registered = chains(registerAll())

    assertTrue(registered.isNotEmpty())
  }

  @Test
  fun `the whole documented command surface is present`() {
    val registered = chains(registerAll()).toSet()

    val expected =
      listOf(
        "shard monitor",
        "shard monitor target",
        "shard monitor add target",
        "shard monitor remove target",
        "shard monitor clear",
        "shard monitor stop",
        "shard monitor list",
        "shard monitor reset",
        "shard monitor settings",
        "shard monitor help",
        "shard monitor output",
        "shard monitor output output",
        "shard monitor output add output",
        "shard monitor output remove output",
        "shard monitor set output output",
        "shard monitor set chat style",
        "shard monitor mode mode",
        "shard monitor set mode mode",
        "shard monitor theme theme",
        "shard monitor set theme theme",
        "shard monitor name mode",
        "shard monitor set name mode",
        "shard monitor ping state",
        "shard monitor set ping state",
        "shard monitor dmg state",
        "shard monitor set dmg state",
        "shard monitor trend state",
        "shard monitor set trend state",
        "shard prob",
        "shard prob target",
      )

    assertTrue(
      expected.all { it in registered },
      "missing: ${expected.filterNot { it in registered }}",
    )
  }
}
