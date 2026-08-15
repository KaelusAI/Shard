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

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.command.ShardCommand
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.database.ViolationDatabase
import ac.shard.player.PlayerDataManager
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import java.util.concurrent.TimeUnit
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

private const val PERCENT_MULTIPLIER = 100.0
private const val WHOLE_PERCENT_DISPLAY_THRESHOLD = 10.0
private const val WHOLE_NUMBER_REMAINDER = 1.0

private const val PERIOD_HOURS_1H = 1L
private const val PERIOD_HOURS_6H = 6L
private const val PERIOD_HOURS_DAY = 24L
private const val PERIOD_HOURS_WEEK = PERIOD_HOURS_DAY * 7L

private enum class StatsPeriod(val label: String, val hours: Long) {
  H1("1h", PERIOD_HOURS_1H),
  H6("6h", PERIOD_HOURS_6H),
  H24("24h", PERIOD_HOURS_DAY),
  D7("7d", PERIOD_HOURS_WEEK);

  fun millis(): Long = TimeUnit.HOURS.toMillis(hours)

  companion object {
    val DEFAULT = H24

    fun parse(raw: String): StatsPeriod? =
      when (raw.lowercase(Locale.ROOT)) {
        "1h" -> H1
        "6h" -> H6
        "24h",
        "1d" -> H24
        "7d",
        "1w" -> D7
        else -> null
      }
  }
}

private fun formatThreshold(value: Double): String {
  return if (value % WHOLE_NUMBER_REMAINDER == 0.0) {
    value.toInt().toString()
  } else {
    String.format(Locale.US, "%.1f", value)
  }
}

class StatsCommand(
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
  private val playerDataManager: PlayerDataManager,
  private val configManager: ConfigManager,
) : ShardCommand {
  private data class StatsSnapshot(
    val period: StatsPeriod,
    val totalFlags: Int,
    val flagsPerHour: String,
    val uniquePlayers: Int,
    val attackers: Int,
    val uniqueViolators: Int,
    val violatorPercent: String,
    val onlinePlayers: Int,
    val suspiciousNow: Long,
    val suspiciousPercent: String,
  )

  override fun register(manager: CommandManager<Sender>) {
    val periodSuggestions =
      SuggestionProvider.suggesting<Sender>(
        StatsPeriod.entries.map { Suggestion.suggestion(it.label) }
      )

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("stats").permission("shard.stats").handler { context ->
        execute(context, StatsPeriod.DEFAULT)
      }
    }

    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("stats")
        .permission("shard.stats")
        .required("period", StringParser.stringParser()) { suggestionProvider = periodSuggestions }
        .handler { context ->
          val raw: String = context["period"]
          val period = StatsPeriod.parse(raw)
          if (period == null) {
            MessageUtil.sendMessage(
              context.sender().nativeSender,
              Message.STATS_INVALID_PERIOD,
              "options",
              StatsPeriod.entries.joinToString("/") { it.label },
            )
            return@handler
          }
          execute(context, period)
        }
    }
  }

  private fun execute(context: CommandContext<Sender>, period: StatsPeriod) {
    val sender = context.sender()
    val db: ViolationDatabase = databaseManager.database
    val since = System.currentTimeMillis() - period.millis()
    val onlinePlayers = Bukkit.getOnlinePlayers().size
    val suspiciousNow = getSuspiciousCount()

    if (!databaseManager.isAvailable) {
      MessageUtil.sendMessage(sender.nativeSender, Message.STORAGE_DEGRADED)
    }

    scheduler.runAsync {
      val uniquePlayers = db.countUniquePlayersSince(since)
      val attackers = db.countAttackersSince(since)
      val totalFlags = db.getLogCount(since)
      val uniqueViolators = db.getUniqueViolatorsSince(since)
      val snapshot =
        StatsSnapshot(
          period = period,
          totalFlags = totalFlags,
          flagsPerHour = formatPerHour(totalFlags, period.hours),
          uniquePlayers = uniquePlayers,
          attackers = attackers,
          uniqueViolators = uniqueViolators,
          violatorPercent = formatPercent(uniqueViolators, maxOf(attackers, uniqueViolators)),
          onlinePlayers = onlinePlayers,
          suspiciousNow = suspiciousNow,
          suspiciousPercent = formatPercent(suspiciousNow, onlinePlayers),
        )

      scheduler.runSync { buildStatsLines(snapshot).forEach(sender::sendMessage) }
    }
  }

  private fun buildStatsLines(snapshot: StatsSnapshot): List<Component> {
    return listOf(
      MessageUtil.getMessage(Message.STATS_HEADER),
      buildFlagsLine(snapshot),
      buildPlayersLine(snapshot),
      buildViolatorsLine(snapshot),
      MessageUtil.getMessage(
        Message.STATS_ONLINE,
        "online_players",
        snapshot.onlinePlayers.toString(),
      ),
      buildSuspiciousLine(snapshot),
    )
  }

  private fun buildFlagsLine(snapshot: StatsSnapshot): Component =
    MessageUtil.getMessage(
        Message.STATS_FLAGS,
        "flags",
        snapshot.totalFlags.toString(),
        "period",
        snapshot.period.label,
        "flags_per_hour",
        snapshot.flagsPerHour,
      )
      .hoverEvent(
        HoverEvent.showText(
          MessageUtil.getMessage(Message.STATS_FLAGS_HOVER, "flags_per_hour", snapshot.flagsPerHour)
        )
      )
      .clickEvent(ClickEvent.runCommand("/shard logs"))

  private fun buildPlayersLine(snapshot: StatsSnapshot): Component =
    MessageUtil.getMessage(
        Message.STATS_PLAYERS,
        "players",
        snapshot.uniquePlayers.toString(),
        "attackers",
        snapshot.attackers.toString(),
        "period",
        snapshot.period.label,
      )
      .hoverEvent(
        HoverEvent.showText(
          MessageUtil.getMessage(
            Message.STATS_PLAYERS_HOVER,
            "players",
            snapshot.uniquePlayers.toString(),
            "attackers",
            snapshot.attackers.toString(),
            "period",
            snapshot.period.label,
          )
        )
      )

  private fun buildViolatorsLine(snapshot: StatsSnapshot): Component =
    MessageUtil.getMessage(
        Message.STATS_VIOLATORS,
        "violators",
        snapshot.uniqueViolators.toString(),
        "violators_percent",
        snapshot.violatorPercent,
        "period",
        snapshot.period.label,
      )
      .hoverEvent(
        HoverEvent.showText(
          MessageUtil.getMessage(
            Message.STATS_VIOLATORS_HOVER,
            "violators_percent",
            snapshot.violatorPercent,
            "period",
            snapshot.period.label,
            "attackers",
            snapshot.attackers.toString(),
          )
        )
      )

  private fun buildSuspiciousLine(snapshot: StatsSnapshot): Component =
    MessageUtil.getMessage(
        Message.STATS_SUSPICIOUS,
        "suspicious_now",
        snapshot.suspiciousNow.toString(),
        "suspicious_percent_now",
        snapshot.suspiciousPercent,
      )
      .hoverEvent(
        HoverEvent.showText(
          MessageUtil.getMessage(
            Message.STATS_SUSPICIOUS_HOVER,
            "suspicious_now",
            snapshot.suspiciousNow.toString(),
            "online_players",
            snapshot.onlinePlayers.toString(),
            "suspicious_threshold",
            formatThreshold(configManager.suspiciousAlertsBuffer),
          )
        )
      )
      .clickEvent(ClickEvent.runCommand("/shard suspicious list"))

  private fun getSuspiciousCount(): Long {
    return playerDataManager
      .getPlayers()
      .asSequence()
      .filter { sp ->
        val check = sp.checkManager.getCheck(AiCheck::class.java)
        check != null && check.buffer > configManager.suspiciousAlertsBuffer
      }
      .count()
      .toLong()
  }

  private fun formatPerHour(totalFlags: Int, hours: Long): String {
    if (hours <= 0L) return "0"
    val rate = totalFlags.toDouble() / hours.toDouble()
    return if (rate >= WHOLE_PERCENT_DISPLAY_THRESHOLD || rate % WHOLE_NUMBER_REMAINDER == 0.0) {
      String.format(Locale.US, "%.0f", rate)
    } else {
      String.format(Locale.US, "%.1f", rate)
    }
  }

  private fun formatPercent(numerator: Number, denominator: Int): String {
    if (denominator <= 0) {
      return "0%"
    }

    val percent = numerator.toDouble() / denominator.toDouble() * PERCENT_MULTIPLIER
    return if (
      percent >= WHOLE_PERCENT_DISPLAY_THRESHOLD || percent % WHOLE_NUMBER_REMAINDER == 0.0
    ) {
      String.format(Locale.US, "%.0f%%", percent)
    } else {
      String.format(Locale.US, "%.1f%%", percent)
    }
  }
}
