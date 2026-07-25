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

import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorOutputKind
import java.util.UUID
import org.bukkit.entity.Player

data class MonitorOutputCapabilities(
  val maxTargets: Int,
  val claimsClientSlot: Boolean,
  val eventDriven: Boolean,
  val requiresClear: Boolean,
)

data class MonitorOutputPolicy(val keepAliveCycles: Int, val minIntervalCycles: Int)

class MonitorRenderContext(
  val viewer: Player,
  val viewerId: UUID,
  val sessionId: Long,
  val chatStyle: MonitorChatStyle,
  val config: MonitorHudRuntimeConfig,
)

interface MonitorOutput {
  val kind: MonitorOutputKind

  val capabilities: MonitorOutputCapabilities

  fun isAvailable(): Boolean

  fun isAvailableFor(viewer: Player): Boolean = isAvailable()

  fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy

  fun attach(context: MonitorRenderContext): Boolean

  fun render(context: MonitorRenderContext, payload: MonitorRenderPayload)

  fun clear(context: MonitorRenderContext)

  fun detach(context: MonitorRenderContext)
}
