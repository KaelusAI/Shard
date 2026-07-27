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

import ac.shard.command.ShardCommand
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorTargetsService
import ac.shard.monitor.hud.StartResult
import ac.shard.monitor.hud.TargetChange
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser

class MonitorCommand(
  private val hudService: MonitorHudService,
  private val targetsService: MonitorTargetsService,
  private val settingsService: MonitorSettingsService,
) : ShardCommand {
  @Suppress("LongMethod")
  override fun register(manager: CommandManager<Sender>) {
    val watched = MonitorSuggestions.watched(targetsService)

    monitorCommand(manager) { handler(this@MonitorCommand::toggleSelf) }
    monitorCommand(manager) {
      required("target", PlayerParser.playerParser())
      handler(this@MonitorCommand::toggleTarget)
    }
    monitorCommand(manager, path = listOf("add")) {
      required("target", PlayerParser.playerParser())
      handler(this@MonitorCommand::addTarget)
    }
    monitorCommand(manager, path = listOf("remove")) {
      required("target", StringParser.stringParser()) { suggestionProvider = watched }
      handler(this@MonitorCommand::removeTarget)
    }
    monitorCommand(manager, path = listOf("clear")) { handler(this@MonitorCommand::clearTargets) }
    monitorCommand(manager, path = listOf("stop")) { handler(this@MonitorCommand::stopMonitor) }
    monitorCommand(
      manager,
      path = listOf("list"),
      permission = "shard.monitor.list",
      playerOnly = false,
    ) {
      handler(this@MonitorCommand::listSessions)
    }

    probCommand(manager) { handler(this@MonitorCommand::deprecatedProb) }
    probCommand(manager) {
      required("target", PlayerParser.playerParser())
      handler(this@MonitorCommand::deprecatedProb)
    }
  }

  private fun toggleSelf(context: CommandContext<Sender>) {
    val player = context.sender().player ?: return
    toggle(player, player)
  }

  private fun toggleTarget(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val target: Player = context["target"]
    if (player.uniqueId != target.uniqueId && !canWatchOthers(player)) {
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_NO_PERMISSION_OTHER)
      return
    }
    toggle(player, target)
  }

  private fun addTarget(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val target: Player = context["target"]
    if (!canWatchMany(player)) {
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_MULTI_NO_PERMISSION)
      return
    }
    val change = targetsService.add(player, target)
    if (change == TargetChange.NO_SESSION) {
      toggle(player, target)
    } else {
      replyToAdd(player, target, change, targetsService, hudService)
    }
  }

  private fun removeTarget(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val name: String = context["target"]
    when (targetsService.remove(player, name)) {
      TargetChange.APPLIED ->
        MessageUtil.sendMessage(
          sender.nativeSender,
          Message.MONITOR_TARGET_REMOVED,
          "player",
          name,
          "count",
          targetsService.size(player.uniqueId).toString(),
        )
      TargetChange.NO_SESSION ->
        MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
      else ->
        MessageUtil.sendMessage(
          sender.nativeSender,
          Message.MONITOR_TARGET_NOT_WATCHED,
          "player",
          name,
        )
    }
  }

  private fun clearTargets(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val cleared = targetsService.clear(player)
    if (cleared == 0) {
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
    } else {
      MessageUtil.sendMessage(
        sender.nativeSender,
        Message.MONITOR_TARGETS_CLEARED,
        "count",
        cleared.toString(),
      )
    }
  }

  private fun stopMonitor(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val names = targetsService.names(player.uniqueId)
    if (names.isEmpty()) {
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
      return
    }
    hudService.stop(player.uniqueId, player)
    MessageUtil.sendMessage(
      sender.nativeSender,
      Message.MONITOR_DISABLED,
      "player",
      names.joinToString(", "),
    )
  }

  private fun listSessions(context: CommandContext<Sender>) {
    val nativeSender = context.sender().nativeSender
    val sessions = hudService.activeSessions
    if (sessions.isEmpty()) {
      MessageUtil.sendMessage(nativeSender, Message.MONITOR_LIST_EMPTY)
      return
    }
    MessageUtil.sendMessage(
      nativeSender,
      Message.MONITOR_LIST_HEADER,
      "count",
      sessions.size.toString(),
    )
    sessions.forEach { session ->
      MessageUtil.sendMessage(
        nativeSender,
        Message.MONITOR_LIST_ENTRY,
        "viewer",
        session.viewer.name,
        "target",
        session.targets.names().joinToString(", "),
        "output",
        session.outputs.joinToString(", ") { it.kind.key },
        "count",
        session.targets.size.toString(),
      )
    }
  }

  private fun deprecatedProb(context: CommandContext<Sender>) {
    MessageUtil.sendMessage(context.sender().nativeSender, Message.MONITOR_PROB_DEPRECATED)
  }

  private fun toggle(viewer: Player, target: Player) {
    val watching = targetsService.names(viewer.uniqueId)
    if (watching.size == 1 && watching.first().equals(target.name, ignoreCase = true)) {
      hudService.stop(viewer.uniqueId, viewer)
      MessageUtil.sendMessage(viewer, Message.MONITOR_DISABLED, "player", target.name)
      return
    }
    when (hudService.start(viewer, target)) {
      StartResult.STARTED ->
        MessageUtil.sendMessage(viewer, Message.MONITOR_ENABLED, "player", target.name)
      StartResult.LIMIT_REACHED ->
        MessageUtil.sendMessage(
          viewer,
          Message.MONITOR_LIMIT_REACHED,
          "limit",
          hudService.runtimeConfig.limits.maxSessions.toString(),
        )
      StartResult.NO_OUTPUT ->
        MessageUtil.sendMessage(
          viewer,
          Message.MONITOR_OUTPUT_DISABLED,
          "output",
          settingsService.getSettings(viewer.uniqueId).outputs.joinToString(", ") { it.key },
          "fallback",
          settingsService.defaults().outputs.joinToString(", ") { it.key },
        )
    }
  }
}
