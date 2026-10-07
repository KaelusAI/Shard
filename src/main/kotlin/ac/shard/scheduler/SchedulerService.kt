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
package ac.shard.scheduler

import ac.shard.Shard
import ac.shard.platform.scheduler.ImmediateTaskHandle
import ac.shard.platform.scheduler.PlatformScheduler
import ac.shard.platform.scheduler.PlatformSchedulerFactory
import ac.shard.platform.scheduler.TaskHandle
import java.util.concurrent.TimeUnit
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class SchedulerService(private val plugin: Shard, private val scheduler: PlatformScheduler) {
  private val folia = PlatformSchedulerFactory.isFolia()

  fun runAsync(task: Runnable): TaskHandle {
    return scheduler.asyncScheduler.runNow(plugin, task)
  }

  fun runSync(task: Runnable): TaskHandle {
    if (!folia && Bukkit.isPrimaryThread()) {
      task.run()
      return ImmediateTaskHandle.sync()
    }
    return scheduler.globalRegionScheduler.run(plugin, task)
  }

  fun runSync(player: Player?, task: Runnable): TaskHandle {
    if (player == null) {
      return runSync(task)
    }
    if (!folia && Bukkit.isPrimaryThread()) {
      task.run()
      return ImmediateTaskHandle.sync()
    }
    val handle = scheduler.entityScheduler.run(player, plugin, task, null)
    return handle ?: ImmediateTaskHandle.notScheduled()
  }

  fun runLater(task: Runnable, delayTicks: Long): TaskHandle {
    return scheduler.globalRegionScheduler.runDelayed(plugin, task, delayTicks)
  }

  fun runLaterAsync(task: Runnable, delayMillis: Long): TaskHandle {
    return scheduler.asyncScheduler.runDelayed(plugin, task, delayMillis, TimeUnit.MILLISECONDS)
  }

  fun runLater(player: Player?, task: Runnable, delayTicks: Long): TaskHandle {
    if (player == null) {
      return runLater(task, delayTicks)
    }
    val handle = scheduler.entityScheduler.runDelayed(player, plugin, task, null, delayTicks)
    return handle ?: ImmediateTaskHandle.notScheduled()
  }

  fun runTimer(task: Runnable, initialDelayTicks: Long, periodTicks: Long): TaskHandle {
    return scheduler.globalRegionScheduler.runAtFixedRate(
      plugin,
      task,
      initialDelayTicks,
      periodTicks,
    )
  }

  fun runTimerAsync(task: Runnable, delayMillis: Long, periodMillis: Long): TaskHandle {
    return scheduler.asyncScheduler.runAtFixedRate(
      plugin,
      task,
      delayMillis,
      periodMillis,
      TimeUnit.MILLISECONDS,
    )
  }

  fun runTimer(
    player: Player?,
    task: Runnable,
    initialDelayTicks: Long,
    periodTicks: Long,
  ): TaskHandle {
    if (player == null) {
      return runTimer(task, initialDelayTicks, periodTicks)
    }
    val handle =
      scheduler.entityScheduler.runAtFixedRate(
        player,
        plugin,
        task,
        null,
        initialDelayTicks,
        periodTicks,
      )
    return handle ?: ImmediateTaskHandle.notScheduled()
  }

  fun cancelTasks() {
    scheduler.asyncScheduler.cancel(plugin)
    scheduler.globalRegionScheduler.cancel(plugin)
  }
}
