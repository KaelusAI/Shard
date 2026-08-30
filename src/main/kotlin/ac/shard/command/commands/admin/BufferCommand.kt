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

import ac.shard.ai.label.LabelKey
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.command.ShardCommand
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.player.PlayerDataManager
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.util.Locale
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

private const val ALL = "all"
private const val AI_CHECK_NAME = "AI"

class BufferCommand(
  private val playerDataManager: PlayerDataManager,
  private val databaseManager: DatabaseManager,
  private val configManager: ConfigManager,
  private val scheduler: SchedulerService,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("buffer")
      permission("shard.buffer.reset")
      literal("reset")
      required("target", PlayerParser.playerParser())
      optional("label", StringParser.stringParser()) { suggestionProvider = trackedLabels() }
      handler(this@BufferCommand::reset)
    }
  }

  private fun trackedLabels(): SuggestionProvider<Sender> = SuggestionProvider.blocking { ctx, _ ->
    val target = ctx.optional<Player>("target").orElse(null)
    val labels =
      target?.let { aiCheck(it)?.trackedLabels() }.orEmpty().filterNot(LabelKey::isReserved)
    (listOf(ALL) + labels.sorted()).map(Suggestion::suggestion)
  }

  private fun reset(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]
    val requested: String = context.getOrDefault("label", ALL)

    val aiCheck = aiCheck(target)
    if (aiCheck == null) {
      MessageUtil.sendMessage(sender, Message.BUFFER_RESET_NO_DATA, "player", target.name)
      return
    }
    if (requested.trim().lowercase(Locale.ROOT) == ALL) {
      resetEverything(sender, target, aiCheck)
    } else {
      resetLabel(sender, target, aiCheck, requested)
    }
  }

  private fun resetLabel(
    sender: CommandSender,
    target: Player,
    aiCheck: AiCheck,
    requested: String,
  ) {
    val tracked = aiCheck.trackedLabels()
    val label = LabelKey.canonical(requested)?.takeIf { it in tracked && !LabelKey.isReserved(it) }
    if (label == null) {
      MessageUtil.sendMessage(
        sender,
        Message.BUFFER_RESET_UNKNOWN_LABEL,
        "player",
        target.name,
        "label",
        requested,
      )
      return
    }

    val cleared = aiCheck.clearBuffer(label) ?: 0.0
    persist(target, aiCheck)
    MessageUtil.sendMessage(
      sender,
      Message.BUFFER_RESET_LABEL,
      "player",
      target.name,
      "label",
      configManager.labelCatalog.displayName(label),
      "buffer",
      format(cleared),
    )
  }

  private fun resetEverything(sender: CommandSender, target: Player, aiCheck: AiCheck) {
    val cleared = aiCheck.clearBuffers()
    persist(target, aiCheck)
    if (cleared.isEmpty()) {
      MessageUtil.sendMessage(sender, Message.BUFFER_RESET_EMPTY, "player", target.name)
      return
    }
    MessageUtil.sendMessage(
      sender,
      Message.BUFFER_RESET_ALL,
      "player",
      target.name,
      "labels",
      describe(cleared),
      "count",
      cleared.size.toString(),
    )
  }

  private fun persist(target: Player, aiCheck: AiCheck) {
    if (!configManager.persistentBufferEnabled) return
    val now = System.currentTimeMillis()
    val threshold = configManager.persistentBufferSaveThreshold
    val remaining = aiCheck.labelBufferSnapshot().filterValues { it >= threshold }
    val uuid = target.uniqueId
    scheduler.runAsync {
      databaseManager.database.saveAiLabelBuffers(uuid, remaining, now)
      if (remaining.isEmpty()) databaseManager.database.saveAiBuffer(uuid, 0.0, 0L)
    }
  }

  private fun describe(cleared: Map<String, Double>): String {
    val catalog = configManager.labelCatalog
    return cleared.entries
      .sortedByDescending { it.value }
      .joinToString(", ") {
        val name = if (LabelKey.isReserved(it.key)) AI_CHECK_NAME else catalog.displayName(it.key)
        "$name ${format(it.value)}"
      }
  }

  private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)

  private fun aiCheck(target: Player): AiCheck? =
    playerDataManager.getPlayer(target)?.checkManager?.getCheck(AiCheck::class.java)
}
