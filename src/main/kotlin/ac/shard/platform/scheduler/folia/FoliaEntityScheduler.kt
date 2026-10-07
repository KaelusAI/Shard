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

import ac.shard.platform.scheduler.EntityScheduler
import ac.shard.platform.scheduler.TaskHandle
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

class FoliaEntityScheduler : EntityScheduler {
  override fun execute(
    entity: Entity,
    plugin: Plugin,
    run: Runnable,
    retired: Runnable?,
    delay: Long,
  ) {
    entity.scheduler.execute(plugin, run, retired, delay)
  }

  override fun run(
    entity: Entity,
    plugin: Plugin,
    task: Runnable,
    retired: Runnable?,
  ): TaskHandle? {
    val scheduled: ScheduledTask? = entity.scheduler.run(plugin, { task.run() }, retired)
    return scheduled?.let { FoliaTaskHandle(it) }
  }

  override fun runDelayed(
    entity: Entity,
    plugin: Plugin,
    task: Runnable,
    retired: Runnable?,
    delayTicks: Long,
  ): TaskHandle? {
    val scheduled: ScheduledTask? =
      entity.scheduler.runDelayed(plugin, { task.run() }, retired, delayTicks.atLeastOneTick())
    return scheduled?.let { FoliaTaskHandle(it) }
  }

  override fun runAtFixedRate(
    entity: Entity,
    plugin: Plugin,
    task: Runnable,
    retired: Runnable?,
    initialDelayTicks: Long,
    periodTicks: Long,
  ): TaskHandle? {
    val scheduled: ScheduledTask? =
      entity.scheduler.runAtFixedRate(
        plugin,
        { task.run() },
        retired,
        initialDelayTicks.atLeastOneTick(),
        periodTicks.atLeastOneTick(),
      )
    return scheduled?.let { FoliaTaskHandle(it) }
  }
}
