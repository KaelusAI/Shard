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
package ac.shard.ai.stream

import ac.shard.data.TickData

object WindowEvents {
  private val BY_NAME: Map<String, Short> =
    linkedMapOf(
      "hit_player" to TickData.START_MELEE_PLAYER,
      "hit_mob" to TickData.START_MELEE_LIVING_OTHER,
      "hit_crystal" to TickData.START_ATTACK_END_CRYSTAL,
      "hit_entity" to TickData.START_ATTACK_ENTITY_OTHER,
      "anchor_use" to TickData.START_USE_RESPAWN_ANCHOR,
      "crystal_place" to TickData.START_PLACE_END_CRYSTAL,
      "explosion" to TickData.START_EXPLOSION_RECEIVED,
    )

  private val BY_KIND: Map<Short, String> = BY_NAME.entries.associate { (k, v) -> v to k }

  fun kind(name: String): Short? = BY_NAME[name]

  fun name(kind: Short): String? = BY_KIND[kind]
}
