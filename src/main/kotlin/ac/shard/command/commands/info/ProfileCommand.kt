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

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.command.ShardCommand
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.database.AiSnapshot
import ac.shard.database.DatabaseManager
import ac.shard.mitigation.ScoreMath
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import ac.shard.utils.TimeUtil
import java.time.Instant
import java.util.Locale
import java.util.logging.Logger
import org.bukkit.OfflinePlayer
import org.bukkit.Statistic
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.OfflinePlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister

private const val PERCENT = 100

class ProfileCommand(
  private val playerDataManager: PlayerDataManager,
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val databaseManager: DatabaseManager,
  private val scheduler: SchedulerService,
  private val logger: Logger,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("profile")
        .permission("shard.profile")
        .required("target", OfflinePlayerParser.offlinePlayerParser())
        .handler(this@ProfileCommand::execute)
    }
  }

  private fun execute(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: OfflinePlayer = context["target"]

    val online = target.player
    val shardPlayer = online?.let { playerDataManager.getPlayer(it) }
    if (online == null || shardPlayer == null) {
      offline(sender, target)
      return
    }

    show(sender, online, shardPlayer)
  }

  private fun show(sender: CommandSender, target: Player, shardPlayer: ShardPlayer) {
    val aiCheck = shardPlayer.checkManager.getCheck(AiCheck::class.java)

    val sessionMillis = System.currentTimeMillis() - shardPlayer.joinTime
    var totalPlayTicks = 0L
    try {
      totalPlayTicks = target.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong()
    } catch (_: IllegalArgumentException) {
      logger.fine("Failed to fetch PLAY_ONE_MINUTE for ${target.name}")
    }

    val totalPlayMillis = totalPlayTicks * 50

    MessageUtil.sendMessageList(
      sender,
      Message.PROFILE_LINES,
      "player",
      target.name,
      "ping",
      target.ping.toString(),
      "version",
      shardPlayer.user.clientVersion.releaseName,
      "brand",
      shardPlayer.brand,
      "session_time",
      TimeUtil.formatDuration(sessionMillis, localeManager),
      "total_playtime",
      TimeUtil.formatDuration(totalPlayMillis, localeManager),
      "ai_buffer",
      if (aiCheck != null) String.format("%.2f", aiCheck.buffer) else "N/A",
      "ai_probs_90",
      if (aiCheck != null) aiCheck.prob90.toString() else "N/A",
      "ai_labels",
      if (aiCheck != null) labelBreakdown(aiCheck.labelBufferSnapshot()) else "N/A",
      "mitigation_tier",
      shardPlayer.mitigation.tierName,
      "mitigation_score",
      String.format(Locale.US, "%.1f", shardPlayer.mitigation.score),
      "mitigation_active",
      shardPlayer.mitigation.applied?.id ?: "-",
      "windows",
      shardPlayer.mitigation.answers.toString(),
      "probabilities",
      histogram(
        shardPlayer.mitigation.shape().let {
          AiSnapshot(0L, it.total, it.low, it.middle, it.high, 0L)
        }
      ),
    )
  }

  private fun offline(sender: CommandSender, target: OfflinePlayer) {
    if (!target.hasPlayedBefore()) {
      MessageUtil.sendMessage(sender, Message.PROFILE_NO_DATA)
      return
    }
    val uuid = target.uniqueId
    val name = target.name ?: uuid.toString()

    scheduler.runAsync {
      val database = databaseManager.database
      val snapshot = database.loadAiSnapshot(uuid)
      val buffer = database.loadAiBuffer(uuid)
      val score = database.loadMitigationScore(uuid)
      val violations = database.getLogCount(uuid)

      val lines =
        MessageUtil.getMessageList(
          Message.PROFILE_OFFLINE_LINES,
          "player",
          name,
          "seen",
          ago(snapshot?.savedAt),
          "ai_buffer",
          number(buffer?.buffer, 2),
          "buffer_seen",
          ago(buffer?.updatedAt),
          "windows",
          text(snapshot?.windows),
          "high",
          text(snapshot?.highWindows),
          "probabilities",
          histogram(snapshot),
          "mitigation_score",
          number(score?.score, 1),
          "sessions",
          score?.sessions?.toString() ?: "0",
          "days",
          score?.days?.toString() ?: "0",
          "violations",
          violations.toString(),
        )

      scheduler.runSync { lines.forEach { MessageUtil.sendMessage(sender, it) } }
    }
  }

  private fun text(value: Long?): String = value?.toString() ?: "-"

  private fun number(value: Double?, digits: Int): String =
    value?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "-"

  private fun ago(millis: Long?): String =
    millis?.let { TimeUtil.formatTimeAgo(Instant.ofEpochMilli(it), localeManager) } ?: "-"

  private fun histogram(snapshot: AiSnapshot?): String {
    val total = snapshot?.let { it.low + it.middle + it.high } ?: 0L
    if (snapshot == null || total == 0L) return "-"
    return localeManager
      .getRawMessage(Message.MITIGATIONS_HISTOGRAM)
      .replace("<low-mark>", ScoreMath.LOW_TAIL_UNTIL.toString())
      .replace("<high-mark>", ScoreMath.SPIKE_FROM.toString())
      .replace("<low>", share(snapshot.low, total))
      .replace("<mid>", share(snapshot.middle, total))
      .replace("<high>", share(snapshot.high, total))
  }

  private fun share(part: Long, total: Long): String = "${part * PERCENT / total}%"

  private fun labelBreakdown(buffers: Map<String, Double>): String {
    val catalog = configManager.labelCatalog
    val visible = catalog.visible(buffers)
    if (visible.isEmpty()) return "-"
    return visible.joinToString(", ") { key ->
      "${catalog.displayName(key)} ${String.format(Locale.US, "%.2f", buffers.getValue(key))}"
    }
  }
}
