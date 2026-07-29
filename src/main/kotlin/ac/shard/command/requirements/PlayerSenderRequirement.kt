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
package ac.shard.command.requirements

import ac.shard.command.SenderRequirement
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import net.kyori.adventure.text.Component
import org.incendo.cloud.context.CommandContext

object PlayerSenderRequirement : SenderRequirement {
  override fun errorMessage(sender: Sender): Component {
    return MessageUtil.getMessage(Message.RUN_AS_PLAYER)
  }

  override fun evaluateRequirement(commandContext: CommandContext<Sender>): Boolean {
    return commandContext.sender().isPlayer
  }
}
