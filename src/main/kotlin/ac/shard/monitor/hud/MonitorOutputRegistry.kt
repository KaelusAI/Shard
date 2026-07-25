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

class MonitorOutputRegistry(outputs: List<MonitorOutput>) {
  private val byKind: Map<MonitorOutputKind, MonitorOutput> = outputs.associateBy { it.kind }

  fun output(kind: MonitorOutputKind): MonitorOutput? = byKind[kind]

  fun capacity(kind: MonitorOutputKind): Int = byKind[kind]?.capabilities?.maxTargets ?: 1

  fun available(config: MonitorHudRuntimeConfig): List<MonitorOutputKind> =
    MonitorOutputKind.entries.filter { kind ->
      val output = byKind[kind]
      output != null && output.isAvailable() && config.isEnabled(kind)
    }
}
