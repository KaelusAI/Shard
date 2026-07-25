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
import ac.shard.monitor.view.MonitorViewService
import ac.shard.monitor.view.VIEW_PERMISSION
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister

class ViewCommand(private val monitorViewService: MonitorViewService) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("view")
        .permission(VIEW_PERMISSION)
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .handler(this@ViewCommand::toggle)
    }
  }

  private fun toggle(context: CommandContext<Sender>) {
    val viewer = context.sender().player ?: return
    val enabled = monitorViewService.toggle(viewer)

    if (enabled) {
      MessageUtil.sendMessage(viewer, Message.VIEW_ENABLED)
    } else {
      MessageUtil.sendMessage(viewer, Message.VIEW_DISABLED)
    }
  }
}
