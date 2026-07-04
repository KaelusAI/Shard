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
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.ApplicableRegionSet
import com.sk89q.worldguard.protection.flags.StateFlag
import java.util.Locale
import org.bukkit.entity.Player

internal class WorldGuardRegionQuery(private val configManager: ConfigManager) {
  private val worldGuard: WorldGuard = WorldGuard.getInstance()

  fun isPlayerInDisabledRegion(player: Player): Boolean {
    val container = worldGuard.platform.regionContainer
    val regions = container.get(BukkitAdapter.adapt(player.world)) ?: return false

    val set =
      regions.getApplicableRegions(
        BlockVector3.at(player.location.x, player.location.y, player.location.z)
      )

    return queryFlag(set) ?: matchLegacyDisabledList(player, set)
  }

  private fun queryFlag(set: ApplicableRegionSet): Boolean? {
    val flag = ShardFlags.checks ?: return null
    return when (set.queryState(null, flag)) {
      StateFlag.State.DENY -> true
      StateFlag.State.ALLOW -> false
      null -> null
    }
  }

  private fun matchLegacyDisabledList(player: Player, set: ApplicableRegionSet): Boolean {
    val disabledRegions = configManager.aiDisabledRegions
    val playerRegions = set.regions.filterNot { it.id.equals("__global__", ignoreCase = true) }
    val topRegion = playerRegions.maxByOrNull { it.priority }
    if (disabledRegions.isEmpty() || topRegion == null) {
      return false
    }
    val worldName = player.world.name.lowercase(Locale.ROOT)
    val regionId = topRegion.id.lowercase(Locale.ROOT)
    return regionId in disabledRegions["*"].orEmpty() ||
      regionId in disabledRegions[worldName].orEmpty()
  }
}
