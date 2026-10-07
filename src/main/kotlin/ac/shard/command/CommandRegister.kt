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
package ac.shard.command

import ac.shard.command.handler.ShardCommandFailureHandler
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import io.leangen.geantyref.TypeToken
import java.util.function.Function
import java.util.logging.Logger
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.format.NamedTextColor
import org.incendo.cloud.exception.ArgumentParseException
import org.incendo.cloud.exception.CommandExecutionException
import org.incendo.cloud.exception.InvalidCommandSenderException
import org.incendo.cloud.exception.InvalidSyntaxException
import org.incendo.cloud.exception.NoPermissionException
import org.incendo.cloud.key.CloudKey
import org.incendo.cloud.processors.requirements.RequirementApplicable
import org.incendo.cloud.processors.requirements.RequirementPostprocessor
import org.incendo.cloud.processors.requirements.Requirements

class CommandRegister(
  private val messages: Messages,
  private val commands: Collection<ShardCommand>,
  private val failureHandler: ShardCommandFailureHandler,
  private val logger: Logger,
) {
  private var commandsRegistered = false

  fun registerCommands(commandManager: org.incendo.cloud.CommandManager<Sender>) {
    if (commandsRegistered) {
      return
    }

    for (command in commands) {
      command.register(commandManager)
    }

    val senderRequirementPostprocessor =
      RequirementPostprocessor.of(REQUIREMENT_KEY, failureHandler)
    commandManager.registerCommandPostProcessor(senderRequirementPostprocessor)

    registerExceptionHandler(commandManager, InvalidSyntaxException::class.java) { e ->
      messages.format(e.correctSyntax())
    }
    registerExceptionHandler(commandManager, NoPermissionException::class.java) {
      messages.getMessage(Message.NO_PERMISSION)
    }
    registerExceptionHandler(commandManager, InvalidCommandSenderException::class.java) {
      messages.getMessage(Message.RUN_AS_PLAYER)
    }
    registerExceptionHandler(commandManager, ArgumentParseException::class.java) { e ->
      messages.getMessage(Message.INVALID_ARGUMENT, "reason", e.cause?.message.orEmpty())
    }
    registerExceptionHandler(commandManager, CommandExecutionException::class.java) { e ->
      val cause = e.cause ?: e
      logger.warning("[Command] ${cause.javaClass.simpleName}: ${cause.message}")
      messages.getMessage(Message.COMMAND_FAILED)
    }

    commandsRegistered = true
  }

  fun startCommands() {
    commands.forEach(ShardCommand::start)
  }

  private fun <E : Exception> registerExceptionHandler(
    commandManager: org.incendo.cloud.CommandManager<Sender>,
    ex: Class<E>,
    toComponent: Function<E, ComponentLike>,
  ) {
    commandManager.exceptionController().registerHandler(ex) { c ->
      c.context()
        .sender()
        .sendMessage(
          toComponent.apply(c.exception()).asComponent().colorIfAbsent(NamedTextColor.RED)
        )
    }
  }

  companion object {
    @JvmField
    val REQUIREMENT_KEY: CloudKey<Requirements<Sender, SenderRequirement>> =
      CloudKey.of(
        "shard_requirements",
        object : TypeToken<Requirements<Sender, SenderRequirement>>() {},
      )

    @JvmField
    val REQUIREMENT_FACTORY:
      RequirementApplicable.RequirementApplicableFactory<Sender, SenderRequirement> =
      RequirementApplicable.factory(REQUIREMENT_KEY)
  }
}
