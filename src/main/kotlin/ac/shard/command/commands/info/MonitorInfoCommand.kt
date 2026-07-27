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
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorOutputRegistry
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext

class MonitorInfoCommand(
  private val settingsService: MonitorSettingsService,
  private val hudService: MonitorHudService,
  private val registry: MonitorOutputRegistry,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    monitorCommand(manager, path = listOf("output")) {
      handler(this@MonitorInfoCommand::showOutputs)
    }
    monitorCommand(manager, path = listOf("settings")) {
      handler(this@MonitorInfoCommand::showSettings)
    }
    monitorCommand(manager, path = listOf("help"), playerOnly = false) {
      handler(this@MonitorInfoCommand::showHelp)
    }
  }

  private fun showOutputs(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    MessageUtil.sendMessage(player, MessageUtil.getMessage(Message.MONITOR_OUTPUT_HEADER))
    MonitorOutputKind.entries.forEach { kind ->
      MessageUtil.sendMessage(player, outputRow(player, kind))
    }
  }

  private fun showSettings(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val settings = settingsService.getSettings(player.uniqueId)
    MessageUtil.sendMessage(player, MessageUtil.getMessage(Message.MONITOR_SETTINGS_HEADER))
    settingRows(settings).forEach { (key, value) ->
      MessageUtil.sendMessage(player, settingRow(key, value))
    }
    MessageUtil.sendMessage(player, MessageUtil.getMessage(Message.MONITOR_SETTINGS_HINT))
  }

  private fun showHelp(context: CommandContext<Sender>) {
    MessageUtil.sendMessageList(context.sender().nativeSender, Message.MONITOR_HELP)
  }

  private fun outputRow(player: Player, kind: MonitorOutputKind): Component =
    MessageUtil.getMessage(
        Message.MONITOR_OUTPUT_ENTRY,
        TagResolver.resolver(
          Placeholder.unparsed("output", kind.key),
          Placeholder.component("status", MessageUtil.getMessage(statusKey(player, kind))),
        ),
      )
      .hoverEvent(
        HoverEvent.showText(
          MessageUtil.getMessage(Message.MONITOR_OUTPUT_ENTRY_HOVER, "output", kind.key)
        )
      )
      .clickEvent(ClickEvent.runCommand(toggleCommand(player, kind)))

  private fun toggleCommand(player: Player, kind: MonitorOutputKind): String {
    val verb = if (kind in settingsService.getSettings(player.uniqueId).outputs) "remove" else "add"
    return "/shard monitor output $verb ${kind.key}"
  }

  private fun statusKey(player: Player, kind: MonitorOutputKind): Message =
    when {
      kind in settingsService.getSettings(player.uniqueId).outputs ->
        Message.MONITOR_OUTPUT_STATE_ACTIVE
      !hudService.runtimeConfig.isEnabled(kind) -> Message.MONITOR_OUTPUT_STATE_OFF
      !registry.isSupported(kind) -> Message.MONITOR_OUTPUT_STATE_UNSUPPORTED
      !player.hasPermission(kind.permission) -> Message.MONITOR_OUTPUT_STATE_LOCKED
      else -> Message.MONITOR_OUTPUT_STATE_AVAILABLE
    }

  private fun settingRow(setting: String, value: String): Component =
    MessageUtil.getMessage(Message.MONITOR_SETTINGS_ENTRY, "setting", setting, "value", value)
      .hoverEvent(
        HoverEvent.showText(
          MessageUtil.getMessage(Message.MONITOR_SETTINGS_ENTRY_HOVER, "setting", setting)
        )
      )
      .clickEvent(ClickEvent.suggestCommand("/shard monitor set $setting "))

  private fun settingRows(settings: MonitorSettings): List<Pair<String, String>> =
    listOf(
      "output" to settings.outputs.joinToString(",") { it.key },
      "mode" to settings.mode.name.lowercase(Locale.ROOT),
      "theme" to settings.theme.name.lowercase(Locale.ROOT),
      "chat" to settings.chatStyle.name.lowercase(Locale.ROOT),
      "name" to settings.showName.name.lowercase(Locale.ROOT),
      "ping" to if (settings.showPing) "on" else "off",
      "dmg" to if (settings.showDmg) "on" else "off",
      "trend" to if (settings.showTrend) "on" else "off",
    )
}
