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

val FIRST_GAMEPLAY_TIER: MitigationTier = MitigationTier.MID

enum class MitigationTier {
  NONE,
  LOW,
  MID,
  HIGH;

  val below: MitigationTier
    get() = if (ordinal == 0) NONE else entries[ordinal - 1]

  fun atLeast(other: MitigationTier): Boolean = ordinal >= other.ordinal

  companion object {
    fun parse(raw: String?): MitigationTier? = entries.firstOrNull {
      it.name.equals(raw?.trim(), ignoreCase = true)
    }
  }
}
