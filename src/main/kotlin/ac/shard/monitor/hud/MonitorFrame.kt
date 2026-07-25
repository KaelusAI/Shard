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

import ac.shard.monitor.core.MonitorSample
import java.util.UUID

enum class MonitorSeverity {
  CALM,
  WATCH,
  ALERT,
}

data class MonitorFrame(
  val targetId: UUID,
  val targetName: String,
  val headline: String,
  val placeholders: Map<String, String>,
  val progress: Float,
  val severity: MonitorSeverity,
  val dataPresent: Boolean,
  val aiActive: Boolean,
)

class MonitorRenderPayload(val frames: List<MonitorFrame>, val samples: Map<UUID, MonitorSample>) {
  val primary: MonitorFrame
    get() = frames[0]
}
