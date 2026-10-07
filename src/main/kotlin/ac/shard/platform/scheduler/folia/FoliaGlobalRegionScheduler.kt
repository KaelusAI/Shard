/*
 * This file is part of GrimAC - https://github.com/GrimAnticheat/Grim
 * Copyright (C) 2021-2026 GrimAC, DefineOutside and contributors
 *
 * GrimAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.platform.scheduler.folia

import ac.shard.platform.scheduler.GlobalRegionScheduler as ShardGlobalRegionScheduler
import ac.shard.platform.scheduler.TaskHandle
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class FoliaGlobalRegionScheduler : ShardGlobalRegionScheduler {
  private val globalRegionScheduler: GlobalRegionScheduler = Bukkit.getGlobalRegionScheduler()

  override fun execute(plugin: Plugin, task: Runnable) {
    globalRegionScheduler.execute(plugin, task)
  }

  override fun run(plugin: Plugin, task: Runnable): TaskHandle {
    return FoliaTaskHandle(globalRegionScheduler.run(plugin) { task.run() })
  }

  override fun runDelayed(plugin: Plugin, task: Runnable, delayTicks: Long): TaskHandle {
    return FoliaTaskHandle(
      globalRegionScheduler.runDelayed(plugin, { task.run() }, delayTicks.atLeastOneTick())
    )
  }

  override fun runAtFixedRate(
    plugin: Plugin,
    task: Runnable,
    initialDelayTicks: Long,
    periodTicks: Long,
  ): TaskHandle {
    return FoliaTaskHandle(
      globalRegionScheduler.runAtFixedRate(
        plugin,
        { task.run() },
        initialDelayTicks.atLeastOneTick(),
        periodTicks.atLeastOneTick(),
      )
    )
  }

  override fun cancel(plugin: Plugin) {
    globalRegionScheduler.cancelTasks(plugin)
  }
}

internal fun Long.atLeastOneTick(): Long = coerceAtLeast(1L)
