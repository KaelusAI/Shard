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

import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.command.CommandRegister
import ac.shard.command.ShardCommand
import ac.shard.command.requirements.PlayerSenderRequirement
import ac.shard.database.DatabaseManager
import ac.shard.player.PlayerDataManager
import ac.shard.redis.CrossServerSuspiciousService
import ac.shard.redis.SuspiciousSnapshot
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister

internal fun dedupeByPlayer(entries: List<SuspiciousSnapshot>): List<SuspiciousSnapshot> =
  entries
    .groupBy { it.uuid }
    .values
    .map { group -> group.reduce { a, b -> if (b.updatedAt >= a.updatedAt) b else a } }

class SuspiciousCommand(
  private val playerDataManager: PlayerDataManager,
  private val alertManager: AlertManager,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
  private val crossServerSuspiciousService: CrossServerSuspiciousService,
) : ShardCommand {
  private data class FlaggedPlayerEntry(val playerName: String, val flags: Int)

  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("suspicious")
        .permission("shard.suspicious")
        .literal("alerts")
        .permission("shard.suspicious.alerts")
        .mutate { it.apply(CommandRegister.REQUIREMENT_FACTORY.create(PlayerSenderRequirement)) }
        .handler(this@SuspiciousCommand::executeAlerts)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("suspicious")
        .permission("shard.suspicious")
        .literal("list")
        .permission("shard.suspicious.list")
        .handler(this@SuspiciousCommand::executeList)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("suspicious")
        .permission("shard.suspicious")
        .literal("top")
        .permission("shard.suspicious.top")
        .handler(this@SuspiciousCommand::executeTop)
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("suspicious")
        .permission("shard.suspicious")
        .literal("flagged")
        .permission("shard.suspicious.flagged")
        .handler(this@SuspiciousCommand::executeFlagged)
    }
  }

  private fun executeAlerts(context: CommandContext<Sender>) {
    val player = context.sender().player ?: return
    alertManager.toggle(player, AlertType.SUSPICIOUS, false)
  }

  private fun executeList(context: CommandContext<Sender>) {
    val sender = context.sender()
    val local = collectLocalSuspicious()

    if (!crossServerSuspiciousService.isActive) {
      renderList(sender, local.sortedByDescending { it.buffer }, tagged = false)
      return
    }

    scheduler.runAsync {
      val merged = dedupeByPlayer(local + fetchRemoteEntries()).sortedByDescending { it.buffer }
      scheduler.runSync { renderList(sender, merged, tagged = true) }
    }
  }

  private fun executeTop(context: CommandContext<Sender>) {
    val sender = context.sender()
    val local = collectLocalSuspicious()

    if (!crossServerSuspiciousService.isActive) {
      renderTop(sender, local.maxByOrNull { it.buffer }, tagged = false)
      return
    }

    scheduler.runAsync {
      val top = dedupeByPlayer(local + fetchRemoteEntries()).maxByOrNull { it.buffer }
      scheduler.runSync { renderTop(sender, top, tagged = true) }
    }
  }

  private fun collectLocalSuspicious(): List<SuspiciousSnapshot> {
    val server = crossServerSuspiciousService.serverName
    val entries = ArrayList<SuspiciousSnapshot>()
    for (sp in playerDataManager.getPlayers()) {
      val check = sp.checkManager.getCheck(AiCheck::class.java) ?: continue
      val mitigation = sp.mitigation
      if (check.buffer > 0.0 || mitigation.matched != null) {
        entries.add(
          SuspiciousSnapshot(
            server,
            sp.uuid.toString(),
            sp.player.name,
            check.buffer,
            sp.player.ping,
            Long.MAX_VALUE,
            mitigation.matched?.let { mitigation.tierName },
            mitigation.score,
          )
        )
      }
    }
    return entries
  }

  private fun fetchRemoteEntries(): List<SuspiciousSnapshot> =
    crossServerSuspiciousService.fetchRemote()

  private fun renderList(sender: Sender, entries: List<SuspiciousSnapshot>, tagged: Boolean) {
    if (entries.isEmpty()) {
      sender.sendMessage(MessageUtil.getMessage(Message.SUSPICIOUS_LIST_EMPTY))
      return
    }

    sender.sendMessage(
      MessageUtil.getMessage(Message.SUSPICIOUS_LIST_HEADER, "count", entries.size.toString())
    )

    for (entry in entries) {
      val line =
        MessageUtil.getMessage(
            Message.SUSPICIOUS_LIST_ENTRY,
            "player",
            entry.name,
            "buffer",
            String.format(Locale.US, "%.1f", entry.buffer),
            "ping",
            entry.ping.toString(),
            "level",
            entry.level ?: "-",
            "score",
            String.format(Locale.US, "%.1f", entry.score),
          )
          .hoverEvent(
            HoverEvent.showText(MessageUtil.getMessage(Message.SUSPICIOUS_LIST_ENTRY_HOVER))
          )
          .clickEvent(ClickEvent.runCommand("/shard profile ${entry.name}"))

      sender.sendMessage(withServerTag(entry.server, line, tagged))
    }
  }

  private fun renderTop(sender: Sender, top: SuspiciousSnapshot?, tagged: Boolean) {
    if (top == null) {
      sender.sendMessage(MessageUtil.getMessage(Message.SUSPICIOUS_TOP_NONE))
      return
    }

    val line =
      MessageUtil.getMessage(
          Message.SUSPICIOUS_TOP_PLAYER,
          "player",
          top.name,
          "buffer",
          String.format(Locale.US, "%.1f", top.buffer),
        )
        .hoverEvent(
          HoverEvent.showText(MessageUtil.getMessage(Message.SUSPICIOUS_TOP_PLAYER_HOVER))
        )
        .clickEvent(ClickEvent.runCommand("/shard monitor ${top.name}"))

    sender.sendMessage(withServerTag(top.server, line, tagged))
  }

  private fun withServerTag(server: String, line: Component, tagged: Boolean): Component =
    if (tagged) {
      MessageUtil.getMessage(Message.CROSS_SERVER_SERVER_TAG, "server", server)
        .append(Component.space())
        .append(line)
    } else {
      line
    }

  private fun executeFlagged(context: CommandContext<Sender>) {
    val sender = context.sender()
    val onlinePlayers =
      Bukkit.getOnlinePlayers()
        .map { player -> player.uniqueId to player.name }
        .toMap(LinkedHashMap())

    if (onlinePlayers.isEmpty()) {
      sender.sendMessage(MessageUtil.getMessage(Message.SUSPICIOUS_FLAGGED_EMPTY))
      return
    }

    scheduler.runAsync {
      val flagCounts = databaseManager.database.getLogCounts(onlinePlayers.keys)
      val flaggedPlayers =
        flagCounts.entries
          .asSequence()
          .filter { it.value > 0 }
          .mapNotNull { (uuid, flags) ->
            onlinePlayers[uuid]?.let { playerName -> FlaggedPlayerEntry(playerName, flags) }
          }
          .sortedWith(compareByDescending<FlaggedPlayerEntry> { it.flags }.thenBy { it.playerName })
          .toList()

      scheduler.runSync {
        if (flaggedPlayers.isEmpty()) {
          sender.sendMessage(MessageUtil.getMessage(Message.SUSPICIOUS_FLAGGED_EMPTY))
          return@runSync
        }

        sender.sendMessage(
          MessageUtil.getMessage(
            Message.SUSPICIOUS_FLAGGED_HEADER,
            "count",
            flaggedPlayers.size.toString(),
          )
        )

        for (entry in flaggedPlayers) {
          sender.sendMessage(
            MessageUtil.getMessage(
                Message.SUSPICIOUS_FLAGGED_ENTRY,
                "player",
                entry.playerName,
                "flags",
                entry.flags.toString(),
              )
              .hoverEvent(
                HoverEvent.showText(MessageUtil.getMessage(Message.SUSPICIOUS_LIST_ENTRY_HOVER))
              )
              .clickEvent(ClickEvent.runCommand("/shard profile ${entry.playerName}"))
          )
        }
      }
    }
  }
}
