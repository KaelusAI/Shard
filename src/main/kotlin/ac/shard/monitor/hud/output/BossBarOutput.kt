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
package ac.shard.monitor.hud.output

import ac.shard.monitor.core.ComponentCache
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.hud.BossBarConfig
import ac.shard.monitor.hud.MAX_BOSS_BARS
import ac.shard.monitor.hud.MonitorFrame
import ac.shard.monitor.hud.MonitorHudRuntimeConfig
import ac.shard.monitor.hud.MonitorOutput
import ac.shard.monitor.hud.MonitorOutputCapabilities
import ac.shard.monitor.hud.MonitorOutputPolicy
import ac.shard.monitor.hud.MonitorRenderContext
import ac.shard.monitor.hud.MonitorRenderPayload
import ac.shard.monitor.hud.MonitorSeverity
import ac.shard.monitor.hud.OutputStates
import ac.shard.monitor.hud.fillFrameTemplate
import java.util.UUID
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.platform.bukkit.BukkitAudiences

class BossBarOutput(private val adventure: BukkitAudiences, private val cache: ComponentCache) :
  MonitorOutput {
  private val states = OutputStates<BossBarState>()

  override val kind = MonitorOutputKind.BOSSBAR

  override val capabilities =
    MonitorOutputCapabilities(
      maxTargets = MAX_BOSS_BARS,
      claimsClientSlot = false,
      eventDriven = false,
      requiresClear = true,
    )

  override fun isAvailable(): Boolean = true

  override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
    MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0)

  override fun attach(context: MonitorRenderContext): Boolean {
    val displaced = states.put(context, BossBarState())
    if (displaced != null && context.viewer.isOnline) {
      displaced.hideAll(adventure.player(context.viewer))
    }
    return true
  }

  override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) {
    val state = states.get(context) ?: return
    if (!context.viewer.isOnline) {
      return
    }
    val config = context.config.bossBar
    val audience = adventure.player(context.viewer)
    val ranked = payload.frames.sortedByDescending { it.progress }
    val shown = ranked.take(config.maxBars)

    hideMissing(state, audience, shown.mapTo(HashSet(shown.size)) { it.targetId })
    shown.forEach { frame -> syncBar(state, audience, frame, config) }
    updateOverflow(state, audience, ranked.size - shown.size, config)
  }

  override fun clear(context: MonitorRenderContext) {
    val state = states.get(context) ?: return
    if (context.viewer.isOnline) {
      state.hideAll(adventure.player(context.viewer))
    }
    state.bars.clear()
    state.overflowBar = null
  }

  override fun detach(context: MonitorRenderContext) {
    clear(context)
    states.remove(context)
  }

  private fun hideMissing(state: BossBarState, audience: Audience, keep: Set<UUID>) {
    val iterator = state.bars.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (!keep.contains(entry.key)) {
        audience.hideBossBar(entry.value)
        iterator.remove()
      }
    }
  }

  private fun syncBar(
    state: BossBarState,
    audience: Audience,
    frame: MonitorFrame,
    config: BossBarConfig,
  ) {
    val title = cache.component(fillFrameTemplate(config.title, frame))
    val progress = if (frame.dataPresent && frame.aiActive) frame.progress else 0f
    val color = colorFor(frame, config)
    val existing = state.bars[frame.targetId]
    if (existing == null) {
      val bar = BossBar.bossBar(title, progress, color, config.overlay)
      state.bars[frame.targetId] = bar
      audience.showBossBar(bar)
      return
    }
    if (existing.name() != title) existing.name(title)
    if (existing.progress() != progress) existing.progress(progress)
    if (existing.color() != color) existing.color(color)
    if (existing.overlay() != config.overlay) existing.overlay(config.overlay)
  }

  private fun colorFor(frame: MonitorFrame, config: BossBarConfig): BossBar.Color {
    if (!frame.dataPresent || !frame.aiActive) {
      return BossBar.Color.WHITE
    }
    return config.fixedColor
      ?: when (frame.severity) {
        MonitorSeverity.ALERT -> BossBar.Color.RED
        MonitorSeverity.WATCH -> BossBar.Color.YELLOW
        MonitorSeverity.CALM -> BossBar.Color.GREEN
      }
  }

  private fun updateOverflow(
    state: BossBarState,
    audience: Audience,
    hidden: Int,
    config: BossBarConfig,
  ) {
    if (hidden <= 0) {
      state.overflowBar?.let { audience.hideBossBar(it) }
      state.overflowBar = null
      return
    }
    val text =
      cache.component(config.overflowTemplate.replace(COUNT_PLACEHOLDER, hidden.toString()))
    val existing = state.overflowBar
    if (existing == null) {
      val bar = BossBar.bossBar(text, 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
      state.overflowBar = bar
      audience.showBossBar(bar)
    } else if (existing.name() != text) {
      existing.name(text)
    }
  }

  internal class BossBarState {
    val bars = LinkedHashMap<UUID, BossBar>()
    var overflowBar: BossBar? = null

    fun hideAll(audience: Audience) {
      bars.values.forEach { audience.hideBossBar(it) }
      overflowBar?.let { audience.hideBossBar(it) }
    }
  }

  private companion object {
    const val COUNT_PLACEHOLDER = "{count}"
  }
}
