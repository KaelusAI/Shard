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

import ac.shard.player.state.TrackingState
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AttackWindowTrackerTest {

  @Test
  fun `the window carries the kind of the event that anchored it`() {
    val tracker = AttackWindowTracker()
    val buffer = TickBuffer(CAPACITY)
    var seen: Short = -1

    tracker.onTick(buffer, true, TickData.START_ATTACK_END_CRYSTAL, POST_WINDOW) { _, _, kind ->
      seen = kind
    }
    repeat(POST_WINDOW) {
      tracker.onTick(buffer, false, TickData.START_MELEE_PLAYER, POST_WINDOW) { _, _, kind ->
        seen = kind
      }
    }

    assertEquals(TickData.START_ATTACK_END_CRYSTAL, seen)
  }

  @Test
  fun `events during a filling window do not change the anchored kind`() {
    val tracker = AttackWindowTracker()
    val buffer = TickBuffer(CAPACITY)
    var seen: Short = -1

    tracker.onTick(buffer, true, TickData.START_MELEE_PLAYER, POST_WINDOW) { _, _, kind ->
      seen = kind
    }
    repeat(POST_WINDOW) {
      tracker.onTick(buffer, true, TickData.START_EXPLOSION_RECEIVED, POST_WINDOW) { _, _, kind ->
        seen = kind
      }
    }

    assertEquals(TickData.START_MELEE_PLAYER, seen)
  }

  @Test
  fun `only a melee hit on a player anchors until an extra anchor is enabled`() {
    val tracking = TrackingState()

    tracking.raiseWindowStart(TickData.START_ATTACK_END_CRYSTAL)
    assertEquals(false, tracking.windowStartThisTick)

    tracking.enabledWindowStarts =
      TrackingState.MELEE_PLAYER_ONLY or (1 shl TickData.START_ATTACK_END_CRYSTAL.toInt())
    tracking.raiseWindowStart(TickData.START_ATTACK_END_CRYSTAL)
    assertEquals(TickData.START_ATTACK_END_CRYSTAL, tracking.windowStartKind)
  }

  @Test
  fun `a melee hit on a player outranks a weaker event in the same tick`() {
    val tracking = TrackingState().apply { enabledWindowStarts = ALL_ANCHORS }

    tracking.raiseWindowStart(TickData.START_EXPLOSION_RECEIVED)
    tracking.raiseWindowStart(TickData.START_MELEE_PLAYER)
    assertEquals(TickData.START_MELEE_PLAYER, tracking.windowStartKind)

    tracking.onTickEnd()
    tracking.raiseWindowStart(TickData.START_MELEE_PLAYER)
    tracking.raiseWindowStart(TickData.START_EXPLOSION_RECEIVED)
    assertEquals(TickData.START_MELEE_PLAYER, tracking.windowStartKind)
  }

  private companion object {
    const val CAPACITY = 64
    const val POST_WINDOW = 4
    const val ALL_ANCHORS = -1
  }
}
