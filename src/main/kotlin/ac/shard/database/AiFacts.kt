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
package ac.shard.database

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.player.ShardPlayer

data class AiFacts(
  val buffer: Double,
  val score: Double,
  val windows: Long,
  val highWindows: Long,
) {
  companion object {
    fun of(shardPlayer: ShardPlayer): AiFacts {
      val aiCheck = shardPlayer.checkManager.getCheck(AiCheck::class.java)
      val state = shardPlayer.mitigation
      return AiFacts(
        buffer = aiCheck?.buffer ?: 0.0,
        score = state.score,
        windows = state.answers,
        highWindows = aiCheck?.prob90?.toLong() ?: 0L,
      )
    }
  }
}
