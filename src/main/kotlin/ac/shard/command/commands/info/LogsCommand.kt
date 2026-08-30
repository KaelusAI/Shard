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
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.database.DatabaseManager
import ac.shard.database.Violation
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import ac.shard.utils.TimeUtil
import net.kyori.adventure.text.Component
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.parser.standard.IntegerParser

class LogsCommand(
  private val databaseManager: DatabaseManager,
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val scheduler: SchedulerService,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("logs")
        .permission("shard.logs")
        .optional("page", IntegerParser.integerParser(1))
        .handler(this@LogsCommand::handleLogs)
    }
  }

  private fun handleLogs(context: CommandContext<Sender>) {
    val sender = context.sender()
    val page: Int = context.getOrDefault("page", 1)

    if (!configManager.config.getBoolean("history.enabled", false)) {
      MessageUtil.sendMessage(sender.nativeSender, Message.HISTORY_DISABLED)
      return
    }

    if (!databaseManager.isAvailable) {
      MessageUtil.sendMessage(sender.nativeSender, Message.STORAGE_DEGRADED)
    }

    scheduler.runAsync {
      val entriesPerPage = 10
      val violations: List<Violation> =
        databaseManager.database.getViolations(page, entriesPerPage, 0L)
      val totalLogs = databaseManager.database.getLogCount(0L)
      val maxPages =
        kotlin.math.max(1, kotlin.math.ceil(totalLogs.toDouble() / entriesPerPage).toInt())

      val header =
        MessageUtil.getMessage(
          Message.LOGS_HEADER,
          "page",
          page.toString(),
          "max_pages",
          maxPages.toString(),
        )

      val entries = violations.map { violation -> renderEntry(violation) }

      scheduler.runSync {
        sender.sendMessage(header)

        if (entries.isEmpty()) {
          MessageUtil.sendMessage(sender.nativeSender, Message.LOGS_NO_VIOLATIONS)
          return@runSync
        }

        for (entry in entries) {
          sender.sendMessage(entry)
        }
      }
    }
  }

  private fun renderEntry(violation: Violation): Component {
    val shown =
      configManager.labelCatalog.format(
        violation.labels.split(',').map(String::trim).filter(String::isNotEmpty)
      )
    return MessageUtil.getMessage(
      Message.LOGS_ENTRY,
      "server",
      violation.serverName,
      "player",
      violation.playerName,
      "check",
      violation.checkName,
      "vl",
      violation.vl.toString(),
      "verbose",
      violation.verbose,
      "timeago",
      TimeUtil.formatTimeAgo(violation.createdAt, localeManager),
      "labels",
      shown,
      "labels_line",
      MessageUtil.labelsLine(shown),
    )
  }
}
