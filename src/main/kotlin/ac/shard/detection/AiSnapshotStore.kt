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
package ac.shard.detection

import ac.shard.database.AiSnapshot
import ac.shard.database.DatabaseManager
import ac.shard.player.ShardPlayer
import ac.shard.utils.WallClock

class AiSnapshotStore(
  private val databaseManager: DatabaseManager,
  private val clock: WallClock,
) {

  fun saveOnQuit(shardPlayer: ShardPlayer) {
    val state = shardPlayer.mitigation
    if (state.answers == 0L) return
    val shape = state.shape()
    databaseManager.database.saveAiSnapshot(
      shardPlayer.uuid,
      AiSnapshot(
        highWindows = shardPlayer.detection.prob90.toLong(),
        windows = state.answers,
        low = shape.low,
        middle = shape.middle,
        high = shape.high,
        savedAt = clock(),
      ),
    )
  }
}
