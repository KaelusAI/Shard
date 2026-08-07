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
package ac.shard.data

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap

class CrystalTracker {
  private val crystalSpawn = Int2LongOpenHashMap()
  private val anchorUse = Long2LongOpenHashMap()

  fun onCrystalSpawn(entityId: Int, tick: Long) {
    if (crystalSpawn.size >= MAX_TRACKED) crystalSpawn.clear()
    crystalSpawn.put(entityId, tick)
  }

  fun onEntityRemoved(entityId: Int) {
    crystalSpawn.remove(entityId)
  }

  fun spawnToAttack(entityId: Int, tick: Long): Int {
    if (!crystalSpawn.containsKey(entityId)) return NONE
    val delta = tick - crystalSpawn.get(entityId)
    return delta.coerceIn(MIN_DELTA.toLong(), MAX_DELTA.toLong()).toInt()
  }

  fun anchorUseInterval(x: Int, y: Int, z: Int, tick: Long): Int {
    val key =
      ((x.toLong() and COORD_MASK) shl X_SHIFT) or
        ((z.toLong() and COORD_MASK) shl Z_SHIFT) or
        (y.toLong() and Y_MASK)
    val previous = if (anchorUse.containsKey(key)) anchorUse.get(key) else NONE.toLong()
    if (anchorUse.size >= MAX_TRACKED) anchorUse.clear()
    anchorUse.put(key, tick)
    if (previous == NONE.toLong()) return NONE
    return (tick - previous).coerceIn(0L, MAX_DELTA.toLong()).toInt()
  }

  fun clear() {
    crystalSpawn.clear()
    anchorUse.clear()
  }

  private companion object {
    const val MAX_TRACKED = 256
    const val NONE = -1
    const val MIN_DELTA = -1024
    const val MAX_DELTA = 1024
    const val COORD_MASK = 0x3FFFFFFL
    const val Y_MASK = 0xFFFL
    const val X_SHIFT = 38
    const val Z_SHIFT = 12
  }
}
