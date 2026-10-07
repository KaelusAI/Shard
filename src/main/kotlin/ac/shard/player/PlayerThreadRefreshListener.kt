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
package ac.shard.player

import ac.shard.scheduler.SchedulerService
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerTeleportEvent

class PlayerThreadRefreshListener(
  private val players: PlayerDataManager,
  private val scheduler: SchedulerService,
) : Listener {
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onTeleport(event: PlayerTeleportEvent) {
    val player = event.player
    scheduler.runLater(player, { players.getPlayer(player)?.refreshOnPlayerThread() }, 1L)
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onWorldChange(event: PlayerChangedWorldEvent) {
    players.getPlayer(event.player)?.refreshOnPlayerThread()
  }
}
