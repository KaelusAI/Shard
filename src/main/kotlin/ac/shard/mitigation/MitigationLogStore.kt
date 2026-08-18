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
package ac.shard.mitigation

import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.database.MitigationLogEntry
import ac.shard.player.ShardPlayer
import java.util.Locale

class MitigationLogStore(
  private val databaseManager: DatabaseManager,
  private val configManager: ConfigManager,
  private val settings: () -> MitigationSettings,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  fun enabled(): Boolean = settings().logEnabled

  fun saveOnQuit(shardPlayer: ShardPlayer) {
    val state = shardPlayer.mitigation
    val rule = state.applied ?: return
    if (state.appliedAtMillis == 0L) return
    record(
      shardPlayer,
      rule,
      state.appliedTier,
      state.appliedAtMillis,
      maxOf(state.peakScore, state.score),
    )
  }

  fun record(
    shardPlayer: ShardPlayer,
    rule: MitigationRule,
    tier: MitigationTier,
    startedAt: Long,
    peak: Double,
  ) {
    if (!enabled()) return
    databaseManager.database.recordMitigation(
      shardPlayer.uuid,
      MitigationLogEntry(
        serverName = configManager.config.getString("history.server-name", "server"),
        playerName = shardPlayer.player.name,
        rule = rule.id,
        tier = tier.name.lowercase(Locale.US),
        score = peak,
        startedAt = startedAt,
        endedAt = clock(),
      ),
    )
  }
}
