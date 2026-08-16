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

import ac.shard.database.DatabaseManager
import ac.shard.database.StoredScore
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService

class MitigationScoreStore(
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
  private val settings: () -> MitigationSettings,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  fun restoreOnLogin(shardPlayer: ShardPlayer) {
    val score = settings().score
    if (!score.persistEnabled) return

    scheduler.runAsync {
      val stored = databaseManager.database.loadMitigationScore(shardPlayer.uuid) ?: return@runAsync
      if (!shardPlayer.player.isOnline) return@runAsync

      val now = clock()
      val age = now - stored.updatedAt
      if (age < 0L || age > score.persistTtlMillis) return@runAsync

      val decayed = ScoreMath.decay(stored.score, age, score.halfLifeMillis)
      scheduler.runSync(shardPlayer.player) {
        if (!shardPlayer.player.isOnline) return@runSync
        shardPlayer.mitigation.restore(decayed, now, score)
        shardPlayer.mitigation.history = ScoreHistory(stored.sessions, stored.days, stored.lastDay)
      }
    }
  }

  fun save(shardPlayer: ShardPlayer) {
    if (!settings().score.persistEnabled) return

    val state = shardPlayer.mitigation
    val seen = state.history
    if (state.score == 0.0 && seen == ScoreHistory.EMPTY) return

    databaseManager.database.saveMitigationScore(
      shardPlayer.uuid,
      StoredScore(
        score = state.score,
        updatedAt = clock(),
        sessions = seen.sessions,
        days = seen.days,
        lastDay = seen.lastDay,
      ),
    )
  }
}
