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
package ac.shard.monitor.hud

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class OutputStates<S : Any> {
  private val states = ConcurrentHashMap<UUID, Held<S>>()

  fun put(context: MonitorRenderContext, state: S): S? {
    val displaced = states.put(context.viewerId, Held(context.sessionId, state))
    return displaced?.value?.takeIf { displaced.sessionId != context.sessionId }
  }

  fun get(context: MonitorRenderContext): S? =
    states[context.viewerId]?.takeIf { it.sessionId == context.sessionId }?.value

  fun remove(context: MonitorRenderContext): S? {
    var removed: S? = null
    states.computeIfPresent(context.viewerId) { _, held ->
      if (held.sessionId == context.sessionId) {
        removed = held.value
        null
      } else {
        held
      }
    }
    return removed
  }

  private class Held<S : Any>(val sessionId: Long, val value: S)
}
