/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
package ac.shard.utils.lists

import it.unimi.dsi.fastutil.doubles.Double2IntMap
import it.unimi.dsi.fastutil.doubles.Double2IntOpenHashMap
import java.util.ArrayDeque
import java.util.Queue

class RunningMode(val maxSize: Int) {
  private val addList: Queue<Double>
  private val popularityMap: Double2IntMap = Double2IntOpenHashMap()

  var modeValue: Double = 0.0
    private set

  var modeCount: Int = 0
    private set

  init {
    require(maxSize != 0) { "There's no mode to a size 0 list!" }
    addList = ArrayDeque(maxSize)
  }

  fun size(): Int = addList.size

  fun add(value: Double) {
    pop()

    for (entry in popularityMap.double2IntEntrySet()) {
      if (kotlin.math.abs(entry.doubleKey - value) < THRESHOLD) {
        entry.setValue(entry.intValue + 1)
        addList.add(entry.doubleKey)
        return
      }
    }

    popularityMap.put(value, 1)
    addList.add(value)
  }

  fun decay() {
    val type = addList.poll() ?: return
    val popularity = popularityMap.get(type)
    if (popularity == 1) {
      popularityMap.remove(type)
    } else {
      popularityMap.put(type, popularity - 1)
    }
  }

  private fun pop() {
    if (addList.size >= maxSize) {
      decay()
    }
  }

  fun clear() {
    addList.clear()
    popularityMap.clear()
    modeValue = 0.0
    modeCount = 0
  }

  fun updateMode() {
    var max = 0
    var mostPopular = 0.0

    for (entry in popularityMap.double2IntEntrySet()) {
      if (entry.intValue > max) {
        max = entry.intValue
        mostPopular = entry.doubleKey
      }
    }

    modeValue = mostPopular
    modeCount = max
  }

  companion object {
    const val THRESHOLD = 1e-3
  }
}
