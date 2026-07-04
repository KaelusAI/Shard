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
package ac.shard.integration

import ac.shard.config.ConfigManager
import ac.shard.region.RegionProvider
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class WorldGuardManager(private val logger: Logger, private val configManager: ConfigManager) :
  RegionProvider {
  private val query: WorldGuardRegionQuery? = createQuery()

  init {
    if (query != null) {
      logger.info("WorldGuard hook enabled.")
    } else {
      logger.info("WorldGuard not found, hook disabled.")
    }
  }

  private fun createQuery(): WorldGuardRegionQuery? {
    if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
      return null
    }
    return try {
      WorldGuardRegionQuery(configManager)
    } catch (error: NoClassDefFoundError) {
      logger.warning("WorldGuard detected but its API is unavailable: ${error.message}")
      null
    }
  }

  override fun isPlayerInDisabledRegion(player: Player): Boolean =
    query?.isPlayerInDisabledRegion(player) ?: false
}
