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

class AttackWindowTracker {
  @Volatile
  var ticksSinceAttack: Int = -1
    private set

  private var attackIndex: Int = -1
  private var anchorBufferId: Int = 0

  fun reset() {
    ticksSinceAttack = -1
    attackIndex = -1
    anchorBufferId = 0
  }

  fun onTick(
    buffer: TickBuffer,
    attackThisTick: Boolean,
    postWindow: Int,
    onWindowReady: (ticksSinceAttack: Int, attackIndex: Int) -> Unit,
  ) {
    val bufferId = System.identityHashCode(buffer)

    if (attackThisTick && ticksSinceAttack < 0) {
      ticksSinceAttack = 0
      attackIndex = buffer.attackIndex()
      anchorBufferId = bufferId
    }

    // The anchored index points into the old buffer, so the window would be cut from foreign rows.
    if (ticksSinceAttack >= 0 && anchorBufferId != bufferId) {
      reset()
      return
    }

    if (ticksSinceAttack < 0) return
    ticksSinceAttack++

    if (ticksSinceAttack >= postWindow) {
      val ticks = ticksSinceAttack
      val anchoredIndex = attackIndex
      reset()
      onWindowReady(ticks, anchoredIndex)
    }
  }
}
