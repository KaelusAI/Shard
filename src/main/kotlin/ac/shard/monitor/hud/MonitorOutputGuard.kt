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

import ac.shard.monitor.core.MonitorOutputKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.entity.Player

fun interface MonitorOutputFailureSink {
  fun onOutputFailed(viewerId: UUID, kind: MonitorOutputKind, phase: String, error: Throwable)
}

@Suppress("TooGenericExceptionCaught")
class MonitorOutputGuard(
  private val delegate: MonitorOutput,
  private val logger: Logger,
  private val sink: MonitorOutputFailureSink,
) : MonitorOutput {
  private val states = ConcurrentHashMap<UUID, GuardState>()

  override val kind: MonitorOutputKind = delegate.kind

  override val capabilities: MonitorOutputCapabilities = delegate.capabilities

  override fun isAvailable(): Boolean = runCatching { delegate.isAvailable() }.getOrDefault(false)

  override fun isAvailableFor(viewer: Player): Boolean =
    runCatching { delegate.isAvailableFor(viewer) }.getOrDefault(false)

  override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
    runCatching { delegate.policy(config) }.getOrDefault(SAFE_POLICY)

  override fun attach(context: MonitorRenderContext): Boolean {
    val state = GuardState(context.sessionId)
    states[context.viewerId] = state
    val attached =
      try {
        delegate.attach(context)
      } catch (throwable: Throwable) {
        logOnce(state, context, PHASE_ATTACH, throwable)
        false
      }
    if (!attached) {
      state.tripped = true
    }
    return attached
  }

  override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) {
    val state = stateFor(context) ?: return
    if (state.tripped) {
      return
    }
    try {
      delegate.render(context, payload)
      state.failures = 0
    } catch (throwable: Throwable) {
      onFailure(state, context, PHASE_RENDER, throwable)
    }
  }

  override fun clear(context: MonitorRenderContext) {
    try {
      delegate.clear(context)
    } catch (throwable: Throwable) {
      stateFor(context)?.let { logOnce(it, context, PHASE_CLEAR, throwable) }
    }
  }

  override fun detach(context: MonitorRenderContext) {
    try {
      delegate.detach(context)
    } catch (throwable: Throwable) {
      stateFor(context)?.let { logOnce(it, context, PHASE_DETACH, throwable) }
    }
    states.computeIfPresent(context.viewerId) { _, held ->
      if (held.sessionId == context.sessionId) null else held
    }
  }

  private fun stateFor(context: MonitorRenderContext): GuardState? =
    states[context.viewerId]?.takeIf { it.sessionId == context.sessionId }

  private fun onFailure(
    state: GuardState,
    context: MonitorRenderContext,
    phase: String,
    throwable: Throwable,
  ) {
    logOnce(state, context, phase, throwable)
    state.failures++
    if (state.failures >= MAX_CONSECUTIVE_FAILURES) {
      state.tripped = true
      sink.onOutputFailed(context.viewerId, kind, phase, throwable)
    }
  }

  private fun logOnce(
    state: GuardState,
    context: MonitorRenderContext,
    phase: String,
    throwable: Throwable,
  ) {
    if (state.logged) {
      return
    }
    state.logged = true
    logger.log(
      Level.WARNING,
      "[Monitor] output ${kind.key} failed during $phase for ${context.viewer.name}",
      throwable,
    )
  }

  private class GuardState(val sessionId: Long) {
    var failures = 0
    var tripped = false
    var logged = false
  }

  private companion object {
    const val MAX_CONSECUTIVE_FAILURES = 3
    const val PHASE_ATTACH = "attach"
    const val PHASE_RENDER = "render"
    const val PHASE_CLEAR = "clear"
    const val PHASE_DETACH = "detach"
    val SAFE_POLICY = MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0)
  }
}
