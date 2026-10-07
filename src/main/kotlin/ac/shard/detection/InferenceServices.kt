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
package ac.shard.detection

import ac.shard.alert.AlertManager
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationScorer
import ac.shard.mitigation.MitigationSkip
import ac.shard.punishment.PunishmentManager
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import ac.shard.utils.Messages
import java.util.logging.Logger

@Suppress("LongParameterList")
class InferenceServices(
  val logger: Logger,
  val configManager: ConfigManager,
  val regionProvider: RegionProvider,
  val alertManager: AlertManager,
  val debugManager: DebugManager,
  val scheduler: SchedulerService,
  val mitigationScorer: MitigationScorer,
  val serverProvider: AIServerProvider,
  val punishments: PunishmentManager,
  val messages: Messages,
  val events: ShardEvents,
  val predictions: Predictions,
  val mitigationSkip: MitigationSkip,
)
