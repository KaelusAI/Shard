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
import ac.shard.monitor.core.MonitorTargetMode
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorTargetsService
import ac.shard.monitor.hud.StartResult
import ac.shard.monitor.hud.TargetChange
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser

@Suppress("TooManyFunctions")
class MonitorCommand(
  private val messages: Messages,
  private val hudService: MonitorHudService,
  private val targetsService: MonitorTargetsService,
  private val settingsService: MonitorSettingsService,
) : ShardCommand {
  private val autoCommand = MonitorAutoCommand(messages, hudService, settingsService)

  @Suppress("LongMethod")
  override fun register(manager: CommandManager<Sender>) {
    val watched = MonitorSuggestions.watched(targetsService)

    monitorCommand(manager) { handler { toggleSelf(it) } }
    monitorCommand(manager) {
      required("target", PlayerParser.playerParser())
      handler { toggleTarget(it) }
    }
    monitorCommand(manager, path = listOf("add")) {
      required("target", PlayerParser.playerParser())
      handler { addTarget(it) }
    }
    monitorCommand(manager, path = listOf("remove")) {
      required("target", StringParser.stringParser()) { suggestionProvider = watched }
      handler { removeTarget(it) }
    }
    monitorCommand(manager, path = listOf("auto")) { handler { watchAuto(it) } }
    monitorCommand(manager, path = listOf("all")) { handler { watchAll(it) } }
    monitorCommand(manager, path = listOf("suspicious")) {
      handler { watchSuspicious(it) }
    }
    monitorCommand(manager, path = listOf("manual")) { handler { watchManual(it) } }
    monitorCommand(manager, path = listOf("clear")) { handler { clearTargets(it) } }
    monitorCommand(manager, path = listOf("stop")) { handler { stopMonitor(it) } }
    monitorCommand(
      manager,
      path = listOf("list"),
      permission = "shard.monitor.list",
      playerOnly = false,
    ) {
      handler { listSessions(it) }
    }

    probCommand(manager) { handler { deprecatedProb(it) } }
    probCommand(manager) {
      required("target", PlayerParser.playerParser())
      handler { deprecatedProb(it) }
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
      messages.sendMessage(sender.nativeSender, Message.MONITOR_NO_PERMISSION_OTHER)
      return
    }
    toggle(player, target)
  }

  private fun addTarget(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val target: Player = context["target"]
    if (!canWatchMany(player)) {
      messages.sendMessage(sender.nativeSender, Message.MONITOR_MULTI_NO_PERMISSION)
      return
    }
    val change = targetsService.add(player, target)
    if (change == TargetChange.NO_SESSION) {
      toggle(player, target)
    } else {
      messages.replyToAdd(player, target, change, targetsService, hudService)
    }
  }

  private fun removeTarget(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val name: String = context["target"]
    when (targetsService.remove(player, name)) {
      TargetChange.APPLIED ->
        messages.sendMessage(
          sender.nativeSender,
          Message.MONITOR_TARGET_REMOVED,
          "player",
          name,
          "count",
          targetsService.size(player.uniqueId).toString(),
        )
      TargetChange.NO_SESSION ->
        messages.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
      else ->
        messages.sendMessage(
          sender.nativeSender,
          Message.MONITOR_TARGET_NOT_WATCHED,
          "player",
          name,
        )
    }
  }

  private fun watchAuto(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    autoCommand.toggle(player, sender.nativeSender, MonitorTargetMode.AUTO)
  }

  private fun watchAll(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    autoCommand.toggle(player, sender.nativeSender, MonitorTargetMode.ALL)
  }

  private fun watchSuspicious(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    autoCommand.toggle(player, sender.nativeSender, MonitorTargetMode.SUSPICIOUS)
  }

  private fun watchManual(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    autoCommand.manual(player, sender.nativeSender)
  }

  private fun clearTargets(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val cleared = targetsService.clear(player)
    if (cleared == 0) {
      messages.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
    } else {
      messages.sendMessage(
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
      messages.sendMessage(sender.nativeSender, Message.MONITOR_NOT_ACTIVE)
      return
    }
    hudService.stop(player.uniqueId, player)
    messages.sendMessage(
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
      messages.sendMessage(nativeSender, Message.MONITOR_LIST_EMPTY)
      return
    }
    messages.sendMessage(
      nativeSender,
      Message.MONITOR_LIST_HEADER,
      "count",
      sessions.size.toString(),
    )
    sessions.forEach { session ->
      messages.sendMessage(
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
    messages.sendMessage(context.sender().nativeSender, Message.MONITOR_PROB_DEPRECATED)
  }

  private fun toggle(viewer: Player, target: Player) {
    val watching = targetsService.names(viewer.uniqueId)
    if (watching.size == 1 && watching.first().equals(target.name, ignoreCase = true)) {
      hudService.stop(viewer.uniqueId, viewer)
      messages.sendMessage(viewer, Message.MONITOR_DISABLED, "player", target.name)
      return
    }
    when (hudService.start(viewer, target)) {
      StartResult.STARTED ->
        messages.sendMessage(viewer, Message.MONITOR_ENABLED, "player", target.name)
      StartResult.LIMIT_REACHED ->
        messages.sendMessage(
          viewer,
          Message.MONITOR_LIMIT_REACHED,
          "limit",
          hudService.runtimeConfig.limits.maxSessions.toString(),
        )
      StartResult.NO_OUTPUT ->
        messages.sendMessage(
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
