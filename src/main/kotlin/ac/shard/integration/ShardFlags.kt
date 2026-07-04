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

import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit

object ShardFlags {
  const val CHECKS_FLAG_NAME = "shard-checks"

  @Volatile
  var checks: StateFlag? = null
    private set

  fun register(logger: Logger) {
    if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
      return
    }

    val registry =
      try {
        WorldGuard.getInstance().flagRegistry
      } catch (failure: NoClassDefFoundError) {
        logger.log(
          Level.FINE,
          "WorldGuard classes unavailable, skipping flag registration",
          failure,
        )
        return
      }

    try {
      val flag = StateFlag(CHECKS_FLAG_NAME, true)
      registry.register(flag)
      checks = flag
    } catch (conflict: FlagConflictException) {
      val existing = registry.get(CHECKS_FLAG_NAME)
      if (existing is StateFlag) {
        checks = existing
      } else {
        logger.log(
          Level.WARNING,
          "Flag $CHECKS_FLAG_NAME already registered with incompatible type",
          conflict,
        )
      }
    } catch (illegal: IllegalStateException) {
      logger.log(Level.WARNING, "Failed to register WorldGuard flag $CHECKS_FLAG_NAME", illegal)
    }
  }
}
