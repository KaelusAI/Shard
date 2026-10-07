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
package ac.shard.command

import ac.shard.database.KnownPlayer
import ac.shard.player.PlayerDirectory
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.MutableCommandBuilder
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

class TargetResolver(
  private val directory: PlayerDirectory,
  private val scheduler: SchedulerService,
  private val messages: Messages,
) {
  private val suggestions: SuggestionProvider<Sender> = SuggestionProvider.blocking { _, _ ->
    directory.suggestions().map(Suggestion::suggestion)
  }

  fun target(
    builder: MutableCommandBuilder<Sender>,
    name: String = TARGET,
  ): MutableCommandBuilder<Sender> =
    builder.required(name, StringParser.stringParser()) { suggestionProvider = suggestions }

  fun resolve(
    context: CommandContext<Sender>,
    name: String = TARGET,
    onFound: (KnownPlayer) -> Unit,
  ) {
    val sender = context.sender()
    val input: String = context[name]
    scheduler.runAsync {
      val found = directory.find(input)
      scheduler.runSync {
        if (found == null) {
          messages.sendMessage(sender.nativeSender, Message.PLAYER_NOT_FOUND, "player", input)
        } else {
          onFound(found)
        }
      }
    }
  }

  private companion object {
    const val TARGET = "target"
  }
}
