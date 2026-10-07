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

import ac.shard.platform.scheduler.RegionScheduler as ShardRegionScheduler
import ac.shard.platform.scheduler.TaskHandle
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin

class FoliaRegionScheduler : ShardRegionScheduler {
  private val regionScheduler: RegionScheduler = Bukkit.getRegionScheduler()

  override fun execute(plugin: Plugin, world: World, chunkX: Int, chunkZ: Int, task: Runnable) {
    regionScheduler.execute(plugin, world, chunkX, chunkZ, task)
  }

  override fun execute(plugin: Plugin, location: Location, task: Runnable) {
    execute(plugin, location.world, location.blockX shr 4, location.blockZ shr 4, task)
  }

  override fun run(
    plugin: Plugin,
    world: World,
    chunkX: Int,
    chunkZ: Int,
    task: Runnable,
  ): TaskHandle {
    return FoliaTaskHandle(regionScheduler.run(plugin, world, chunkX, chunkZ) { task.run() })
  }

  override fun run(plugin: Plugin, location: Location, task: Runnable): TaskHandle {
    return run(plugin, location.world, location.blockX shr 4, location.blockZ shr 4, task)
  }

  override fun runDelayed(
    plugin: Plugin,
    world: World,
    chunkX: Int,
    chunkZ: Int,
    task: Runnable,
    delayTicks: Long,
  ): TaskHandle {
    return FoliaTaskHandle(
      regionScheduler.runDelayed(
        plugin,
        world,
        chunkX,
        chunkZ,
        { task.run() },
        delayTicks.atLeastOneTick(),
      )
    )
  }

  override fun runDelayed(
    plugin: Plugin,
    location: Location,
    task: Runnable,
    delayTicks: Long,
  ): TaskHandle {
    return runDelayed(
      plugin,
      location.world,
      location.blockX shr 4,
      location.blockZ shr 4,
      task,
      delayTicks,
    )
  }

  override fun runAtFixedRate(
    plugin: Plugin,
    world: World,
    chunkX: Int,
    chunkZ: Int,
    task: Runnable,
    initialDelayTicks: Long,
    periodTicks: Long,
  ): TaskHandle {
    return FoliaTaskHandle(
      regionScheduler.runAtFixedRate(
        plugin,
        world,
        chunkX,
        chunkZ,
        { task.run() },
        initialDelayTicks.atLeastOneTick(),
        periodTicks.atLeastOneTick(),
      )
    )
  }

  override fun runAtFixedRate(
    plugin: Plugin,
    location: Location,
    task: Runnable,
    initialDelayTicks: Long,
    periodTicks: Long,
  ): TaskHandle {
    return runAtFixedRate(
      plugin,
      location.world,
      location.blockX shr 4,
      location.blockZ shr 4,
      task,
      initialDelayTicks,
      periodTicks,
    )
  }
}
