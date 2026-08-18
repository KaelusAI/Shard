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
import ac.shard.command.ShardCommand
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.database.MitigationLogEntry
import ac.shard.mitigation.MitigationRuntime
import ac.shard.mitigation.MitigationSkip
import ac.shard.mitigation.MitigationState
import ac.shard.mitigation.ScoreMath
import ac.shard.mitigation.SkipReason
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import ac.shard.utils.TimeUtil
import java.time.Instant
import java.util.Locale
import net.kyori.adventure.text.Component
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.OfflinePlayerParser
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister

private const val MAX_ROWS = 20
private const val PERCENT = 100
private const val LOG_LIMIT = 10

@Suppress("LongParameterList", "TooManyFunctions")
internal class MitigationsCommand(
  private val playerDataManager: PlayerDataManager,
  private val configManager: ConfigManager,
  private val skip: MitigationSkip,
  private val runtime: MitigationRuntime,
  private val localeManager: ac.shard.config.LocaleManager,
  private val alertManager: AlertManager,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
) : ShardCommand {

  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("mitigations").permission("shard.mitigations").handler(this@MitigationsCommand::list)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("mitigations")
        .permission("shard.mitigations")
        .required("target", PlayerParser.playerParser())
        .handler(this@MitigationsCommand::explain)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("mitigations")
        .literal("alerts")
        .permission("shard.mitigations.alerts")
        .handler(this@MitigationsCommand::alerts)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("mitigations")
        .literal("logs")
        .permission("shard.mitigations")
        .handler(this@MitigationsCommand::logs)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("mitigations")
        .literal("history")
        .permission("shard.mitigations")
        .required("target", OfflinePlayerParser.offlinePlayerParser())
        .handler(this@MitigationsCommand::history)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("mitigations")
        .literal("clear")
        .permission("shard.mitigations.clear")
        .required("target", PlayerParser.playerParser())
        .handler(this@MitigationsCommand::clear)
    }
  }

  private fun list(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val settings = configManager.mitigationSettings

    if (!settings.enabled) {
      MessageUtil.sendMessage(sender, Message.MITIGATIONS_DISABLED)
      return
    }

    val rows =
      playerDataManager
        .getPlayers()
        .filter { it.mitigation.matched != null }
        .sortedByDescending { it.mitigation.score }

    if (rows.isEmpty()) {
      MessageUtil.sendMessage(sender, Message.MITIGATIONS_NOBODY)
      return
    }

    MessageUtil.sendMessage(sender, Message.MITIGATIONS_HEADER, "count", rows.size.toString())
    rows.take(MAX_ROWS).forEach { shardPlayer ->
      val state = shardPlayer.mitigation
      MessageUtil.sendMessage(
        sender,
        Message.MITIGATIONS_ROW,
        "player",
        shardPlayer.player.name,
        "tier",
        state.tierName,
        "score",
        format(state.score),
        "active",
        state.applied?.id ?: "waiting",
        "for",
        TimeUtil.formatDuration(
          System.currentTimeMillis() - state.matchedSinceMillis,
          localeManager,
        ),
      )
    }
  }

  private fun explain(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]
    val shardPlayer = playerDataManager.getPlayer(target)

    if (shardPlayer == null) {
      MessageUtil.sendMessage(sender, Message.MITIGATIONS_NO_DATA)
      return
    }

    val state = shardPlayer.mitigation
    val settings = configManager.mitigationSettings
    val reason = skip.skipReason(shardPlayer)

    MessageUtil.sendMessageList(
      sender,
      Message.MITIGATIONS_DETAIL,
      "player",
      target.name,
      "tier",
      state.tierName,
      "applied",
      state.applied?.id ?: "-",
      "waiting",
      pendingText(state),
      "score",
      format(state.score),
      "windows",
      "${state.answers} / ${settings.score.minAnswers}",
      "sessions",
      state.history.sessions.toString(),
      "days",
      state.history.days.toString(),
      "active",
      state.activeEffects.entries
        .joinToString(" · ") { "${it.key} ${format(it.value)}" }
        .ifEmpty { "-" },
      "skipped",
      skipText(reason),
      "histogram",
      histogram(shardPlayer),
    )
  }

  private fun history(context: CommandContext<Sender>) {
    val sender = context.sender()
    val target: OfflinePlayer = context["target"]

    if (!target.hasPlayedBefore() && !target.isOnline) {
      MessageUtil.sendMessage(sender.nativeSender, Message.PLAYER_NOT_FOUND)
      return
    }
    warnIfStorageDegraded(sender)

    val name = target.name ?: target.uniqueId.toString()
    val uuid = target.uniqueId
    scheduler.runAsync {
      val entries =
        databaseManager.database.getMitigationLog(uuid, LOG_LIMIT).map { entry ->
          entryLine(Message.MITIGATIONS_HISTORY_ENTRY, entry)
        }
      scheduler.runSync {
        if (entries.isEmpty()) {
          MessageUtil.sendMessage(
            sender.nativeSender,
            Message.MITIGATIONS_HISTORY_EMPTY,
            "player",
            name,
          )
          return@runSync
        }
        sender.sendMessage(
          MessageUtil.getMessage(Message.MITIGATIONS_HISTORY_HEADER, "player", name)
        )
        entries.forEach { sender.sendMessage(it) }
      }
    }
  }

  private fun logs(context: CommandContext<Sender>) {
    val sender = context.sender()

    warnIfStorageDegraded(sender)

    scheduler.runAsync {
      val entries =
        databaseManager.database.getMitigationLog(LOG_LIMIT).map { entry ->
          entryLine(Message.MITIGATIONS_LOGS_ENTRY, entry)
        }
      scheduler.runSync {
        if (entries.isEmpty()) {
          MessageUtil.sendMessage(sender.nativeSender, Message.MITIGATIONS_LOGS_EMPTY)
          return@runSync
        }
        sender.sendMessage(MessageUtil.getMessage(Message.MITIGATIONS_LOGS_HEADER))
        entries.forEach { sender.sendMessage(it) }
      }
    }
  }

  private fun warnIfStorageDegraded(sender: Sender) {
    if (!databaseManager.isAvailable) {
      MessageUtil.sendMessage(sender.nativeSender, Message.STORAGE_DEGRADED)
    }
  }

  private fun entryLine(key: Message, entry: MitigationLogEntry): Component =
    MessageUtil.getMessage(
      key,
      "server",
      entry.serverName,
      "player",
      entry.playerName,
      "rule",
      entry.rule,
      "tier",
      entry.tier,
      "score",
      format(entry.score),
      "for",
      TimeUtil.formatDuration(entry.endedAt - entry.startedAt, localeManager),
      "ago",
      TimeUtil.formatTimeAgo(Instant.ofEpochMilli(entry.endedAt), localeManager),
    )

  private fun alerts(context: CommandContext<Sender>) {
    val player = context.sender().player ?: return
    alertManager.toggle(player, AlertType.MITIGATION, false)
  }

  private fun pendingText(state: MitigationState): String {
    val left = state.onsetAtMillis - System.currentTimeMillis()
    if (state.matched == null || state.matched === state.applied || left <= 0L) return ""
    return localeManager
      .getRawMessage(Message.MITIGATIONS_PENDING)
      .replace("<rule>", state.matched?.id.orEmpty())
      .replace("<time>", TimeUtil.formatDuration(left, localeManager))
  }

  private fun skipText(reason: SkipReason?): String =
    localeManager.getRawMessage(
      when (reason) {
        null -> Message.MITIGATIONS_SKIP_NONE
        SkipReason.TURNED_OFF -> Message.MITIGATIONS_SKIP_TURNED_OFF
        SkipReason.EXEMPT -> Message.MITIGATIONS_SKIP_EXEMPT
        SkipReason.CHECKS_DISABLED -> Message.MITIGATIONS_SKIP_CHECKS_DISABLED
        SkipReason.NO_MITIGATE -> Message.MITIGATIONS_SKIP_NO_MITIGATE
        SkipReason.BEDROCK -> Message.MITIGATIONS_SKIP_BEDROCK
        SkipReason.DISABLED_REGION -> Message.MITIGATIONS_SKIP_DISABLED_REGION
        SkipReason.TOO_FEW_ANSWERS -> Message.MITIGATIONS_SKIP_TOO_FEW_ANSWERS
      }
    )

  private fun clear(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]
    val shardPlayer = playerDataManager.getPlayer(target)

    if (shardPlayer == null) {
      MessageUtil.sendMessage(sender, Message.MITIGATIONS_NO_DATA)
      return
    }

    shardPlayer.mitigation.clearScore(System.currentTimeMillis())
    runtime.clearFor(shardPlayer)
    MessageUtil.sendMessage(sender, Message.MITIGATIONS_CLEARED, "player", target.name)
  }

  private fun histogram(shardPlayer: ShardPlayer): String {
    val shape = shardPlayer.mitigation.shape()
    if (shape.total == 0L) return "-"
    return "under ${ScoreMath.LOW_TAIL_UNTIL}: ${share(shape.low, shape.total)}  " +
      "between: ${share(shape.middle, shape.total)}  " +
      "over ${ScoreMath.SPIKE_FROM}: ${share(shape.high, shape.total)}"
  }

  private fun share(part: Long, total: Long): String = "${part * PERCENT / total}%"

  private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
}
