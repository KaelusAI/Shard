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

import ac.shard.command.CommandRegister
import ac.shard.command.ShardCommand
import ac.shard.command.requirements.PlayerSenderRequirement
import ac.shard.command.shardCommand
import ac.shard.config.ConfigManager
import ac.shard.monitor.view.MonitorViewService
import ac.shard.monitor.view.VIEW_PERMISSION
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import java.util.Locale
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

class ViewCommand(
  private val messages: Messages,
  private val monitorViewService: MonitorViewService,
  private val configManager: ConfigManager,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("view")
        .permission(VIEW_PERMISSION)
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .handler { toggle(it) }
    }
    manager.shardCommand {
      literal("view")
      literal("model")
      permission(VIEW_PERMISSION)
      mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
      required("model", StringParser.stringParser()) {
        suggestionProvider =
          SuggestionProvider.blocking<Sender> { _, _ ->
            (listOf(AUTO) + modelIds()).map(Suggestion::suggestion)
          }
      }
      handler { pinModel(it) }
    }
  }

  private fun modelIds(): List<String> =
    configManager.streamProfile?.active?.map { it.id }.orEmpty()

  private fun toggle(context: CommandContext<Sender>) {
    val viewer = context.sender().player ?: return
    val enabled = monitorViewService.toggle(viewer)

    if (enabled) {
      messages.sendMessage(viewer, Message.VIEW_ENABLED)
    } else {
      messages.sendMessage(viewer, Message.VIEW_DISABLED)
    }
  }

  private fun pinModel(context: CommandContext<Sender>) {
    val viewer = context.sender().player ?: return
    val raw = context.get<String>("model").trim().lowercase(Locale.ROOT)
    val model = configManager.streamProfile?.active?.firstOrNull { it.id == raw }
    when {
      raw == AUTO -> {
        monitorViewService.pinModel(viewer, null)
        messages.sendMessage(viewer, Message.VIEW_MODEL_AUTO)
      }
      model == null ->
        messages.sendMessage(
          viewer,
          Message.VIEW_MODEL_UNKNOWN,
          "model",
          raw,
          "models",
          modelIds().joinToString(", "),
        )
      else -> {
        monitorViewService.pinModel(viewer, model.id)
        messages.sendMessage(viewer, Message.VIEW_MODEL_PINNED, "model", model.displayTitle)
      }
    }
  }

  private companion object {
    const val AUTO = "auto"
  }
}
