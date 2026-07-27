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

import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.scheduler.SchedulerService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.entity.Player

enum class StartResult {
  STARTED,
  NO_OUTPUT,
  LIMIT_REACHED,
}

@Suppress("LongParameterList")
class MonitorHudService(
  private val scheduler: SchedulerService,
  private val settingsService: MonitorSettingsService,
  private val sampler: MonitorSampler,
  private val frameBuilder: MonitorFrameBuilder,
  private val registry: MonitorOutputRegistry,
  private val index: MonitorTargetIndex,
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val logger: Logger,
) : MonitorOutputFailureSink {
  private val sessions = ConcurrentHashMap<UUID, MonitorHudSession>()
  private val sessionIds = AtomicLong()

  @Volatile
  var runtimeConfig: MonitorHudRuntimeConfig =
    MonitorHudRuntimeConfig.fromManager(configManager, logger)
    private set

  fun start(viewer: Player, target: Player): StartResult {
    val viewerId = viewer.uniqueId
    stop(viewerId, viewer)
    val allowed =
      withinLimits(runtimeConfig.limits, sessions.size, index.viewersOf(target.uniqueId).size)
    val session = if (allowed) openSession(viewer, target) else null
    if (session == null) {
      return if (allowed) StartResult.NO_OUTPUT else StartResult.LIMIT_REACHED
    }
    sessions[viewerId] = session
    index.set(viewerId, session.targets.ids())
    session.task =
      scheduler.runTimer(viewer, Runnable { tick(viewerId) }, 1L, session.config.updateTicks)
    return StartResult.STARTED
  }

  fun stop(viewerId: UUID, viewerHint: Player?) {
    val session = sessions.remove(viewerId) ?: return
    session.cancelled.set(true)
    session.task?.cancel()
    index.clear(viewerId)
    scheduler.runSync(viewerHint ?: session.viewer) { session.teardown() }
  }

  fun stopAll() {
    sessions.keys.toList().forEach { stop(it, null) }
  }

  fun restart(viewerId: UUID) {
    val session = sessions[viewerId] ?: return
    val viewer = session.viewer
    val carried = session.targets.ids()
    stop(viewerId, viewer)
    if (viewer.isOnline) {
      scheduler.runSync(viewer) { reopen(viewer, carried) }
    }
  }

  fun reload() {
    runtimeConfig = MonitorHudRuntimeConfig.fromManager(configManager, logger)
    sessions.keys.toList().forEach { restart(it) }
  }

  val activeSessions: List<MonitorHudSession>
    get() = sessions.values.toList()

  fun session(viewerId: UUID): MonitorHudSession? = sessions[viewerId]

  override fun onOutputFailed(
    viewerId: UUID,
    kind: MonitorOutputKind,
    phase: String,
    error: Throwable,
  ) {
    val session = sessions[viewerId] ?: return
    logger.fine("[Monitor] dropping ${session.viewer.name} from ${kind.key} after a $phase failure")
    scheduler.runSync(session.viewer) {
      registry.output(kind)?.let {
        it.clear(session.context)
        it.detach(session.context)
      }
      session.dropOutput(kind)
      if (session.outputs.isEmpty()) {
        stop(viewerId, session.viewer)
      }
    }
  }

  private fun openSession(viewer: Player, target: Player): MonitorHudSession? {
    val config = runtimeConfig
    val settings = settingsService.getSettings(viewer.uniqueId)
    val resolved =
      registry.resolveAll(settings.outputs, settingsService.defaults().outputs, viewer, config)
    if (resolved.isEmpty()) {
      return null
    }
    val session =
      MonitorHudSession(
        MonitorSessionSpec(
          viewer = viewer,
          sessionId = sessionIds.incrementAndGet(),
          chatStyle = settings.chatStyle,
          config = config,
        ),
        resolved,
      )
    session.trackTarget(target, targetTexts(localeManager, target.name))
    session.outputs.toList().forEach { output ->
      if (!output.attach(session.context)) {
        output.detach(session.context)
        session.dropOutput(output.kind)
      }
    }
    return session.takeIf { it.outputs.isNotEmpty() }
  }

  private fun reopen(viewer: Player, carried: List<UUID>) {
    val players = carried.mapNotNull { Bukkit.getPlayer(it) }.filter { it.isOnline }
    val session =
      players
        .firstOrNull()
        ?.takeIf { start(viewer, it) == StartResult.STARTED }
        ?.let { sessions[viewer.uniqueId] }
    if (session != null) {
      val capacity = effectiveCapacity(session.outputs, session.config)
      players.drop(1).take(capacity - 1).forEach {
        session.trackTarget(it, targetTexts(localeManager, it.name))
      }
      index.set(viewer.uniqueId, session.targets.ids())
    }
  }

  private fun tick(viewerId: UUID) {
    val session = sessions[viewerId]
    if (session == null || session.cancelled.get()) {
      return
    }
    val targets = session.targets.ids().mapNotNull { Bukkit.getPlayer(it) }.filter { it.isOnline }
    if (!session.viewer.isOnline || targets.isEmpty()) {
      stop(viewerId, session.viewer)
      return
    }
    session.render(
      targets.map(sampler::sample),
      settingsService.getSettings(viewerId),
      frameBuilder,
    )
  }
}
