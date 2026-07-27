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
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorSettingsService
import ac.shard.monitor.core.MonitorTheme
import ac.shard.monitor.hud.MonitorHudService
import ac.shard.monitor.hud.MonitorOutputRegistry
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser

class MonitorSettingsCommand(
  private val settingsService: MonitorSettingsService,
  private val hudService: MonitorHudService,
  private val registry: MonitorOutputRegistry,
  private val selector: MonitorOutputSelector,
) : ShardCommand {
  @Suppress("LongMethod")
  override fun register(manager: CommandManager<Sender>) {
    val outputs = MonitorSuggestions.outputs(hudService, registry)

    registerMonitorSetting(manager, "mode", "mode", MonitorSuggestions.MODE, this::setMode)
    registerMonitorSetting(manager, "theme", "theme", MonitorSuggestions.THEME, this::setTheme)
    registerMonitorSetting(manager, "name", "mode", MonitorSuggestions.NAME, this::setNameMode)
    registerMonitorSetting(manager, "ping", "state", MonitorSuggestions.TOGGLE) { ctx ->
      setFlag(ctx, SettingKey.PING)
    }
    registerMonitorSetting(manager, "dmg", "state", MonitorSuggestions.TOGGLE) { ctx ->
      setFlag(ctx, SettingKey.DMG)
    }
    registerMonitorSetting(manager, "trend", "state", MonitorSuggestions.TOGGLE) { ctx ->
      setFlag(ctx, SettingKey.TREND)
    }

    monitorCommand(manager, path = listOf("set", "chat")) {
      required("style", StringParser.stringParser()) {
        suggestionProvider = MonitorSuggestions.CHAT
      }
      handler(this@MonitorSettingsCommand::setChatStyle)
    }
    listOf(listOf("set", "output"), listOf("output")).forEach { path ->
      monitorCommand(manager, path = path) {
        required("output", StringParser.stringParser()) { suggestionProvider = outputs }
        handler { ctx -> changeOutput(ctx, OutputChange.SET) }
      }
    }
    monitorCommand(manager, path = listOf("output", "add")) {
      required("output", StringParser.stringParser()) { suggestionProvider = outputs }
      handler { ctx -> changeOutput(ctx, OutputChange.ADD) }
    }
    monitorCommand(manager, path = listOf("output", "remove")) {
      required("output", StringParser.stringParser()) { suggestionProvider = outputs }
      handler { ctx -> changeOutput(ctx, OutputChange.REMOVE) }
    }
    monitorCommand(manager, path = listOf("reset")) {
      handler(this@MonitorSettingsCommand::resetSettings)
    }
  }

  private fun changeOutput(context: CommandContext<Sender>, mode: OutputChange) {
    val player = context.sender().player ?: return
    val raw: String = context["output"]
    val current = settingsService.getSettings(player.uniqueId).outputs
    val next = selector.resolve(player, raw, mode, current) ?: return
    applySetting(
      player,
      { it.copy(outputs = next) },
      Message.MONITOR_OUTPUT_UPDATED,
      next.joinToString(", ") { kind -> kind.key },
    )
  }

  private fun setMode(context: CommandContext<Sender>) {
    val raw: String = context["mode"]
    val parsed = MonitorMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    applyOrReject(context, "mode", parsed?.let { { s: MonitorSettings -> s.copy(mode = it) } }, raw)
  }

  private fun setTheme(context: CommandContext<Sender>) {
    val raw: String = context["theme"]
    val parsed = MonitorTheme.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    applyOrReject(
      context,
      "theme",
      parsed?.let { { s: MonitorSettings -> s.copy(theme = it) } },
      raw,
    )
  }

  private fun setNameMode(context: CommandContext<Sender>) {
    val raw: String = context["mode"]
    val parsed = MonitorNameMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    applyOrReject(
      context,
      "name",
      parsed?.let { { s: MonitorSettings -> s.copy(showName = it) } },
      raw,
    )
  }

  private fun setChatStyle(context: CommandContext<Sender>) {
    val raw: String = context["style"]
    val parsed = MonitorChatStyle.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    applyOrReject(
      context,
      "chat",
      parsed?.let { { s: MonitorSettings -> s.copy(chatStyle = it) } },
      raw,
    )
  }

  private fun setFlag(context: CommandContext<Sender>, key: SettingKey) {
    val raw: String = context["state"]
    val enabled = parseToggle(raw)
    val mutator = enabled?.let {
      { settings: MonitorSettings ->
        when (key) {
          SettingKey.PING -> settings.copy(showPing = it)
          SettingKey.DMG -> settings.copy(showDmg = it)
          SettingKey.TREND -> settings.copy(showTrend = it)
        }
      }
    }
    applyOrReject(context, key.label, mutator, if (enabled == true) "on" else "off")
  }

  private fun resetSettings(context: CommandContext<Sender>) {
    val sender = context.sender()
    val player = sender.player ?: return
    val before = settingsService.getSettings(player.uniqueId)
    settingsService.mutate(player, { settingsService.defaults() }) { updated ->
      restartIfSessionShapeChanged(hudService, player, before, updated)
      MessageUtil.sendMessage(sender.nativeSender, Message.MONITOR_RESET)
    }
  }

  private fun applyOrReject(
    context: CommandContext<Sender>,
    setting: String,
    mutator: ((MonitorSettings) -> MonitorSettings)?,
    value: String,
  ) {
    val sender = context.sender()
    val player = sender.player ?: return
    if (mutator == null) {
      MessageUtil.sendMessage(
        sender.nativeSender,
        Message.MONITOR_INVALID_SETTING,
        "setting",
        setting,
        "options",
        optionsFor(setting),
      )
      return
    }
    applySetting(player, mutator, Message.MONITOR_SETTING_UPDATED, value, setting)
  }

  private fun applySetting(
    player: Player,
    mutator: (MonitorSettings) -> MonitorSettings,
    message: Message,
    value: String,
    setting: String? = null,
  ) {
    val before = settingsService.getSettings(player.uniqueId)
    settingsService.mutate(player, mutator) { updated ->
      restartIfSessionShapeChanged(hudService, player, before, updated)
      if (setting == null) {
        MessageUtil.sendMessage(player, message, "output", value)
      } else {
        MessageUtil.sendMessage(player, message, "setting", setting, "value", value)
      }
    }
  }

  internal enum class SettingKey(val label: String) {
    PING("ping"),
    DMG("dmg"),
    TREND("trend"),
  }
}

internal fun parseToggle(raw: String?): Boolean? =
  when {
    raw == null -> null
    raw.equals("on", ignoreCase = true) || raw.equals("true", ignoreCase = true) -> true
    raw.equals("off", ignoreCase = true) || raw.equals("false", ignoreCase = true) -> false
    else -> null
  }

internal fun optionsFor(setting: String): String =
  when (setting) {
    "mode" -> MonitorMode.entries.joinToString("/") { it.name.lowercase(Locale.ROOT) }
    "theme" -> MonitorTheme.entries.joinToString("/") { it.name.lowercase(Locale.ROOT) }
    "name" -> MonitorNameMode.entries.joinToString("/") { it.name.lowercase(Locale.ROOT) }
    "chat" -> MonitorChatStyle.entries.joinToString("/") { it.name.lowercase(Locale.ROOT) }
    else -> "on/off"
  }
