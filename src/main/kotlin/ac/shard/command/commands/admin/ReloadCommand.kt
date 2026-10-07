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
package ac.shard.command.commands.admin

import ac.shard.ShardReloader
import ac.shard.command.ShardCommand
import ac.shard.command.shardCommand
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext

class ReloadCommand(
  private val messages: Messages,
  private val reloader: ShardReloader,
  private val scheduler: SchedulerService,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("reload").permission("shard.reload").handler { execute(it) }
    }
  }

  private fun execute(context: CommandContext<Sender>) {
    val sender = context.sender().nativeSender
    messages.sendMessage(sender, Message.RELOAD_START)
    scheduler.runSync {
      reloader.reload()
      messages.sendMessage(sender, Message.RELOAD_SUCCESS)
    }
  }
}
