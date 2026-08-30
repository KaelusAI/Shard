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
import kotlin.test.assertEquals
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
    assertEquals(registered.size, registered.toSet().size, "two commands claim the same chain")
  }

  @Test
  fun `the whole documented command surface is present`() {
    val registered = chains(registerAll()).toSet()

    val expected =
      listOf(
        "shards monitor",
        "shards monitor target",
        "shards monitor add target",
        "shards monitor remove target",
        "shards monitor clear",
        "shards monitor auto",
        "shards monitor all",
        "shards monitor suspicious",
        "shards monitor manual",
        "shards monitor stop",
        "shards monitor list",
        "shards monitor reset",
        "shards monitor settings",
        "shards monitor help",
        "shards monitor output",
        "shards monitor output output",
        "shards monitor output add output",
        "shards monitor output remove output",
        "shards monitor set output output",
        "shards monitor set chat style",
        "shards monitor mode mode",
        "shards monitor set mode mode",
        "shards monitor theme theme",
        "shards monitor set theme theme",
        "shards monitor name mode",
        "shards monitor set name mode",
        "shards monitor ping state",
        "shards monitor set ping state",
        "shards monitor dmg state",
        "shards monitor set dmg state",
        "shards monitor trend state",
        "shards monitor set trend state",
        "shards prob",
        "shards prob target",
      )

    assertTrue(
      expected.all { it in registered },
      "missing: ${expected.filterNot { it in registered }}",
    )
  }
}
