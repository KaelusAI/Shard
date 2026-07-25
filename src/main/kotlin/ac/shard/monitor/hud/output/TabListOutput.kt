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
import ac.shard.monitor.hud.MonitorHudRuntimeConfig
import ac.shard.monitor.hud.MonitorOutput
import ac.shard.monitor.hud.MonitorOutputCapabilities
import ac.shard.monitor.hud.MonitorOutputPolicy
import ac.shard.monitor.hud.MonitorRenderContext
import ac.shard.monitor.hud.MonitorRenderPayload
import ac.shard.monitor.hud.fillFrameTemplate
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component

class TabListOutput(private val adventure: BukkitAudiences, private val cache: ComponentCache) :
  MonitorOutput {
  override val kind = MonitorOutputKind.TABLIST

  override val capabilities =
    MonitorOutputCapabilities(
      maxTargets = 1,
      claimsClientSlot = false,
      eventDriven = false,
      requiresClear = true,
    )

  override fun isAvailable(): Boolean = true

  override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy =
    MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0)

  override fun attach(context: MonitorRenderContext): Boolean = true

  override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) {
    if (!context.viewer.isOnline) {
      return
    }
    val config = context.config.tabList
    val frame = payload.primary
    adventure
      .player(context.viewer)
      .sendPlayerListHeaderAndFooter(
        cache.component(fillFrameTemplate(config.header, frame)),
        cache.component(fillFrameTemplate(config.footer, frame)),
      )
  }

  override fun clear(context: MonitorRenderContext) {
    if (!context.viewer.isOnline) {
      return
    }
    adventure
      .player(context.viewer)
      .sendPlayerListHeaderAndFooter(Component.empty(), Component.empty())
  }

  override fun detach(context: MonitorRenderContext) = Unit
}
