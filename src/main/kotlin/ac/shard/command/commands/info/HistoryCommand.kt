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
package ac.shard.command.commands.info

import ac.shard.command.ShardCommand
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.database.DatabaseManager
import ac.shard.database.Violation
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import ac.shard.utils.Sparkline
import ac.shard.utils.TimeUtil
import java.util.Locale
import net.kyori.adventure.text.Component
import org.bukkit.OfflinePlayer
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.OfflinePlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.description.Description
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.parser.standard.IntegerParser

private const val SPARKLINE_WIDTH = 20
private const val DEFAULT_SERVER_NAME = "server"

class HistoryCommand(
  private val databaseManager: DatabaseManager,
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val scheduler: SchedulerService,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("history", Description.empty(), "hist")
        .permission("shard.history")
        .required("target", OfflinePlayerParser.offlinePlayerParser())
        .optional("page", IntegerParser.integerParser(1))
        .handler(this@HistoryCommand::handleHistory)
    }
  }

  private fun handleHistory(context: CommandContext<Sender>) {
    val sender = context.sender()
    val target: OfflinePlayer = context["target"]
    val page: Int = context.getOrDefault("page", 1)

    if (!configManager.config.getBoolean("history.enabled", false)) {
      MessageUtil.sendMessage(sender.nativeSender, Message.HISTORY_DISABLED)
      return
    }
    if (!target.hasPlayedBefore() && !target.isOnline) {
      MessageUtil.sendMessage(sender.nativeSender, Message.PLAYER_NOT_FOUND)
      return
    }
    warnIfStorageDegraded(sender)
    val targetId = target.uniqueId

    scheduler.runAsync {
      val entriesPerPage = 10
      val violations: List<Violation> =
        databaseManager.database.getViolations(targetId, page, entriesPerPage)
      val totalLogs = databaseManager.database.getLogCount(targetId)
      val maxPages =
        kotlin.math.max(1, kotlin.math.ceil(totalLogs.toDouble() / entriesPerPage).toInt())

      val header =
        MessageUtil.getMessage(
          Message.HISTORY_HEADER,
          "player",
          displayName(target),
          "page",
          page.toString(),
          "max_pages",
          maxPages.toString(),
        )

      val here =
        configManager.config.getString("history.server-name", DEFAULT_SERVER_NAME).orEmpty()
      val named = here.isNotBlank() && here != DEFAULT_SERVER_NAME
      val elsewhere = violations.any { it.serverName.isNotBlank() && it.serverName != here }
      val entries = violations.map { violation -> entryLine(violation, named || elsewhere) }

      scheduler.runSync {
        sender.sendMessage(header)

        if (entries.isEmpty()) {
          MessageUtil.sendMessage(sender.nativeSender, Message.HISTORY_NO_VIOLATIONS)
          return@runSync
        }

        for (entry in entries) {
          sender.sendMessage(entry)
        }
      }
    }
  }

  private fun entryLine(violation: Violation, showServer: Boolean): Component =
    MessageUtil.getMessage(
      Message.HISTORY_ENTRY,
      "server",
      serverTag(violation.serverName, showServer),
      "check",
      violation.checkName,
      "vl",
      violation.vl.toString(),
      "verbose",
      violation.verbose,
      "timeago",
      TimeUtil.formatTimeAgo(violation.createdAt, localeManager),
      "buffer",
      violation.aiBuffer?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
      "score",
      violation.mitigationScore?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
      "windows",
      violation.windows?.toString() ?: "-",
      "high",
      violation.highWindows?.toString() ?: "-",
      "trail",
      Sparkline.of(violation.trail, SPARKLINE_WIDTH).ifEmpty { "-" },
    )

  private fun serverTag(name: String, show: Boolean): String =
    if (!show || name.isBlank()) {
      ""
    } else {
      "<dark_gray>[<white>${MessageUtil.escape(name)}</white>]</dark_gray> "
    }

  private fun warnIfStorageDegraded(sender: Sender) {
    if (!databaseManager.isAvailable) {
      MessageUtil.sendMessage(sender.nativeSender, Message.STORAGE_DEGRADED)
    }
  }

  private fun displayName(target: OfflinePlayer): String {
    return target.name ?: target.uniqueId.toString()
  }
}
