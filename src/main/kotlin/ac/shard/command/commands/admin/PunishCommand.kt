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

import ac.shard.command.ShardCommand
import ac.shard.database.DatabaseManager
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import org.bukkit.OfflinePlayer
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.OfflinePlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister

class PunishCommand(private val databaseManager: DatabaseManager) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("punish")
        .permission("shards.punish.manage")
        .literal("reset")
        .required("target", OfflinePlayerParser.offlinePlayerParser())
        .handler(this@PunishCommand::reset)
    }
  }

  private fun reset(context: CommandContext<Sender>) {
    val sender = context.sender()
    val target: OfflinePlayer = context["target"]

    if (!databaseManager.isAvailable) {
      MessageUtil.sendMessage(sender.nativeSender, Message.STORAGE_DEGRADED)
    }

    databaseManager.database.resetAllViolationLevels(target.uniqueId)

    MessageUtil.sendMessage(
      sender.nativeSender,
      Message.PUNISH_RESET_SUCCESS,
      "player",
      target.name ?: target.uniqueId.toString(),
    )
  }
}
