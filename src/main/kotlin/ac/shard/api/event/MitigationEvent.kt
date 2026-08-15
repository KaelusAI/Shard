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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.api.event

import java.util.UUID

data class MitigationEvent(
  val playerId: UUID,
  val playerName: String,
  val mitigationId: String,
  val tier: String,
  val score: Double,
  override var cancelled: Boolean = false,
) : ShardCancellableEvent

data class MitigationRuleEvent(
  val playerId: UUID,
  val playerName: String,
  val fromRule: String,
  val toRule: String,
  val score: Double,
  val reason: String,
) : ShardEvent
