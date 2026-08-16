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
package ac.shard.debug

import ac.shard.Shard
import ac.shard.config.ConfigManager
import java.util.EnumSet

class DebugManager(private val plugin: Shard, private val configManager: ConfigManager) {
  private val enabledCategories: MutableSet<DebugCategory> =
    EnumSet.noneOf(DebugCategory::class.java)

  init {
    reload()
  }

  fun reload() {
    enabledCategories.clear()
    enabledCategories.addAll(configManager.enabledDebugCategories)
  }

  fun isEnabled(category: DebugCategory): Boolean {
    return enabledCategories.contains(category)
  }

  fun log(category: DebugCategory, message: String) {
    if (isEnabled(category)) {
      plugin.logger.info("[DEBUG | ${category.name}] $message")
    }
  }
}
