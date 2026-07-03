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
import ac.shard.config.LocaleManager
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import ac.shard.utils.TimeUtil
import java.util.logging.Logger
import org.bukkit.Statistic
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister

class ProfileCommand(
  private val playerDataManager: PlayerDataManager,
  private val localeManager: LocaleManager,
  private val logger: Logger,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("profile")
        .permission("shard.profile")
        .required("target", PlayerParser.playerParser())
        .handler(this@ProfileCommand::execute)
    }
  }

  private fun execute(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]

    val shardPlayer: ShardPlayer =
      playerDataManager.getPlayer(target)
        ?: run {
          MessageUtil.sendMessage(sender, Message.PROFILE_NO_DATA)
          return
        }

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
    )
  }
}
