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
package ac.shard.command.commands.info

import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorTheme
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorOutputRegistry
import ac.shard.monitor.hud.MonitorTargetsService
import ac.shard.sender.Sender
import java.util.Locale
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

internal object MonitorSuggestions {
  val MODE: SuggestionProvider<Sender> = ofEnum(MonitorMode.entries.map { it.name })
  val THEME: SuggestionProvider<Sender> = ofEnum(MonitorTheme.entries.map { it.name })
  val NAME: SuggestionProvider<Sender> = ofEnum(MonitorNameMode.entries.map { it.name })
  val CHAT: SuggestionProvider<Sender> = ofEnum(MonitorChatStyle.entries.map { it.name })
  val TOGGLE: SuggestionProvider<Sender> =
    SuggestionProvider.suggesting(listOf("on", "off").map { Suggestion.suggestion(it) })

  fun outputs(
    hudService: MonitorHudService,
    registry: MonitorOutputRegistry,
  ): SuggestionProvider<Sender> = SuggestionProvider.blocking { context, _ ->
    val sender = context.sender()
    val config = hudService.runtimeConfig
    MonitorOutputKind.entries
      .filter { kind ->
        config.isEnabled(kind) &&
          registry.isSupported(kind) &&
          sender.nativeSender.hasPermission(kind.permission)
      }
      .map { Suggestion.suggestion(it.key) }
  }

  fun watched(targets: MonitorTargetsService): SuggestionProvider<Sender> =
    SuggestionProvider.blocking { context, _ ->
      val viewer = context.sender().player
      if (viewer == null) {
        emptyList()
      } else {
        targets.names(viewer.uniqueId).map { Suggestion.suggestion(it) }
      }
    }

  private fun ofEnum(names: List<String>): SuggestionProvider<Sender> =
    SuggestionProvider.suggesting(names.map { Suggestion.suggestion(it.lowercase(Locale.ROOT)) })
}
