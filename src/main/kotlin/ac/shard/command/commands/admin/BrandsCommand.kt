/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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

import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.command.ShardCommand
import ac.shard.command.shardCommand
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext

class BrandsCommand(
  private val messages: Messages,
  private val alertManager: AlertManager,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("brands").permission("shard.brand").handler { execute(it) }
    }
  }

  private fun execute(context: CommandContext<Sender>) {
    val nativeSender: CommandSender = context.sender().nativeSender

    if (nativeSender is Player) {
      alertManager.toggle(nativeSender, AlertType.BRAND, false)
    } else {
      alertManager.toggleConsoleAlerts(AlertType.BRAND)
      if (alertManager.isConsoleAlertsEnabled(AlertType.BRAND)) {
        messages.sendMessage(nativeSender, Message.BRAND_ALERTS_ENABLED)
      } else {
        messages.sendMessage(nativeSender, Message.BRAND_ALERTS_DISABLED)
      }
    }
  }
}
