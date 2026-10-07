/*
 * This file is part of GrimAC - https://github.com/GrimAnticheat/Grim
 * Copyright (C) 2021-2026 GrimAC, DefineOutside and contributors
 *
 * GrimAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.command.handler

import ac.shard.command.SenderRequirement
import ac.shard.sender.Sender
import ac.shard.utils.Messages
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.processors.requirements.RequirementFailureHandler

class ShardCommandFailureHandler(private val messages: Messages) :
  RequirementFailureHandler<Sender, SenderRequirement> {
  override fun handleFailure(context: CommandContext<Sender>, requirement: SenderRequirement) {
    context.sender().sendMessage(messages.getMessage(requirement.errorMessage))
  }
}
