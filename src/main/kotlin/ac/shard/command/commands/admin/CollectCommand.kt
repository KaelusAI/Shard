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
import ac.shard.data.CollectManager
import ac.shard.player.PlayerDataManager
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.time.Duration
import java.time.Instant
import java.util.Locale
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.description.Description
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

class CollectCommand(
  private val collectManager: CollectManager,
  private val playerDataManager: PlayerDataManager,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    val typeSuggestions = listOf("LEGIT", "CHEAT").map { Suggestion.suggestion(it) }

    val typeProvider = SuggestionProvider.suggesting<Sender>(typeSuggestions)

    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("collect", Description.empty(), "dc")
        .literal("start")
        .permission("shards.collect.start")
        .required("target", PlayerParser.playerParser())
        .required("type", StringParser.stringParser()) { suggestionProvider = typeProvider }
        .optional("details", StringParser.greedyStringParser())
        .handler(this@CollectCommand::start)
    }

    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("collect", Description.empty(), "dc")
        .literal("stop")
        .permission("shards.collect.stop")
        .required("target", PlayerParser.playerParser())
        .handler(this@CollectCommand::stop)
    }

    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("collect", Description.empty(), "dc")
        .literal("cancel")
        .permission("shards.collect.cancel")
        .required("target", PlayerParser.playerParser())
        .handler(this@CollectCommand::cancel)
    }

    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("collect", Description.empty(), "dc")
        .literal("status")
        .permission("shards.collect.status")
        .optional("target", PlayerParser.playerParser())
        .handler(this@CollectCommand::status)
    }
  }

  private fun start(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]
    val type = context.get<String>("type").uppercase(Locale.ROOT)
    val details: String = context.getOrDefault("details", "")

    val label = resolveLabel(type, details, sender) ?: return

    val shardPlayer = playerDataManager.getPlayer(target)
    if (shardPlayer == null) {
      MessageUtil.sendMessage(sender, Message.COLLECT_STATUS_NO_SESSION, "player", target.name)
      return
    }
    if (collectManager.startCollecting(shardPlayer, label)) {
      MessageUtil.sendMessage(
        sender,
        Message.COLLECT_START_SUCCESS,
        "player",
        target.name,
        "status",
        label,
      )
    } else {
      collectManager.stopCollecting(shardPlayer.uuid)
      collectManager.startCollecting(shardPlayer, label)
      MessageUtil.sendMessage(sender, Message.COLLECT_START_RESTARTED, "player", target.name)
    }
  }

  private fun stop(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]

    if (collectManager.stopCollecting(target.uniqueId)) {
      MessageUtil.sendMessage(sender, Message.COLLECT_STOP_SUCCESS, "player", target.name)
    } else {
      MessageUtil.sendMessage(sender, Message.COLLECT_STOP_FAIL, "player", target.name)
    }
  }

  private fun cancel(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]

    if (collectManager.cancelCollecting(target.uniqueId)) {
      MessageUtil.sendMessage(sender, Message.COLLECT_CANCEL_SUCCESS, "player", target.name)
    } else {
      MessageUtil.sendMessage(sender, Message.COLLECT_STOP_FAIL, "player", target.name)
    }
  }

  private fun status(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player? = context.getOrDefault("target", null)

    if (target != null) {
      val session = collectManager.getSession(target.uniqueId)
      if (session != null) {
        val seconds = Duration.between(session.startTime, Instant.now()).toSeconds()
        MessageUtil.sendMessage(
          sender,
          Message.COLLECT_STATUS_PLAYER,
          "player",
          target.name,
          "status",
          session.label,
          "time",
          seconds.toString(),
          "ticks",
          session.windowCount().toString(),
        )
      } else {
        MessageUtil.sendMessage(
          sender,
          Message.COLLECT_STATUS_NO_SESSION,
          "player",
          target.name,
        )
      }
      return
    }

    MessageUtil.sendMessage(sender, Message.COLLECT_STATUS_HEADER)
    if (collectManager.activeSessions.isEmpty()) {
      MessageUtil.sendMessage(sender, Message.COLLECT_STATUS_NONE)
      return
    }

    for (session in collectManager.activeSessions.values) {
      val seconds = Duration.between(session.startTime, Instant.now()).toSeconds()
      MessageUtil.sendMessage(
        sender,
        Message.COLLECT_STATUS_PLAYER,
        "player",
        session.playerName,
        "status",
        session.label,
        "time",
        seconds.toString(),
        "ticks",
        session.windowCount().toString(),
      )
    }
  }

  private fun resolveLabel(type: String, details: String, sender: CommandSender): String? {
    return when (type) {
      "LEGIT",
      "CHEAT" -> {
        if (details.isEmpty()) {
          MessageUtil.sendMessage(sender, Message.COLLECT_DETAILS_REQUIRED)
          null
        } else {
          "$type $details"
        }
      }
      else -> {
        MessageUtil.sendMessage(sender, Message.COLLECT_INVALID_TYPE)
        null
      }
    }
  }
}
