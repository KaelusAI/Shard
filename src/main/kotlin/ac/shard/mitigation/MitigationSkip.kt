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
@file:Suppress("ReturnCount")

package ac.shard.mitigation

import ac.shard.config.ConfigManager
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionProvider

const val NO_MITIGATE_PERMISSION = "shards.nomitigate"

enum class SkipReason {
  TURNED_OFF,
  EXEMPT,
  CHECKS_DISABLED,
  NO_MITIGATE,
  BEDROCK,
  DISABLED_REGION,
  TOO_FEW_ANSWERS,
}

class MitigationSkip(
  private val configManager: ConfigManager,
  private val regionProvider: RegionProvider,
  private val settings: () -> MitigationSettings,
) {

  fun skipReason(shardPlayer: ShardPlayer): SkipReason? {
    val config = settings()
    if (!config.enabled) return SkipReason.TURNED_OFF

    val player = shardPlayer.player
    val skip = config.skip

    if (shardPlayer.exemptManager.isDisabled(player)) return SkipReason.CHECKS_DISABLED
    if (shardPlayer.exemptManager.isExempt(player)) return SkipReason.EXEMPT
    if (player.hasPermission(NO_MITIGATE_PERMISSION)) return SkipReason.NO_MITIGATE
    if (skip.bedrock && shardPlayer.isBedrockExempt) return SkipReason.BEDROCK
    if (
      skip.followAiRegions &&
        configManager.isAiWorldGuardEnabled() &&
        regionProvider.isPlayerInDisabledRegion(player)
    ) {
      return SkipReason.DISABLED_REGION
    }

    return null
  }
}
