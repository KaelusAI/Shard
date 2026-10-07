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

import ac.shard.ai.label.DetectionKey
import ac.shard.api.impl.CommandInitiator
import ac.shard.api.impl.event.BufferResetEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.api.impl.labelIdOfAddress
import ac.shard.command.ShardCommand
import ac.shard.command.shardCommand
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.detection.DetectionState
import ac.shard.detection.PlayerInference
import ac.shard.player.PlayerDataManager
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import java.util.Locale
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.CommandManager
import org.incendo.cloud.bukkit.parser.PlayerParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.suggestionProvider
import org.incendo.cloud.parser.standard.StringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

private const val ALL = "all"

class BufferCommand(
  private val messages: Messages,
  private val playerDataManager: PlayerDataManager,
  private val databaseManager: DatabaseManager,
  private val configManager: ConfigManager,
  private val scheduler: SchedulerService,
  private val events: ShardEvents,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("buffer")
      permission("shard.buffer.reset")
      literal("reset")
      required("target", PlayerParser.playerParser())
      optional("label", StringParser.stringParser()) { suggestionProvider = trackedLabels() }
      handler { reset(it) }
    }
  }

  private fun trackedLabels(): SuggestionProvider<Sender> = SuggestionProvider.blocking { ctx, _ ->
    val target = ctx.optional<Player>("target").orElse(null)
    val labels =
      target
        ?.let { aiCheck(it)?.trackedLabels() }
        .orEmpty()
        .filterNot(configManager.labelCatalog::hidden)
    (listOf(ALL) + labels.sorted()).map(Suggestion::suggestion)
  }

  private fun reset(context: CommandContext<Sender>) {
    val sender: CommandSender = context.sender().nativeSender
    val target: Player = context["target"]
    val requested: String = context.getOrDefault("label", ALL)

    val aiCheck = aiCheck(target)
    if (aiCheck == null) {
      messages.sendMessage(sender, Message.BUFFER_RESET_NO_DATA, "player", target.name)
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
    aiCheck: DetectionState,
    requested: String,
  ) {
    val tracked = aiCheck.trackedLabels()
    val label =
      DetectionKey.selector(requested)?.takeIf {
        it in tracked && !configManager.labelCatalog.hidden(it)
      }
    if (label == null) {
      messages.sendMessage(
        sender,
        Message.BUFFER_RESET_UNKNOWN_LABEL,
        "player",
        target.name,
        "label",
        requested,
      )
      return
    }

    val key = aiCheck.keyOf(label)
    val cleared = clear(target, aiCheck) { it == key }[key] ?: 0.0
    announce(sender, target, key.model, labelIdOfAddress(label, key.model))
    messages.sendMessage(
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

  private fun resetEverything(sender: CommandSender, target: Player, aiCheck: DetectionState) {
    val cleared = clear(target, aiCheck) { true }.mapKeys { aiCheck.address(it.key) }
    announce(sender, target, null, null)
    if (cleared.isEmpty()) {
      messages.sendMessage(sender, Message.BUFFER_RESET_EMPTY, "player", target.name)
      return
    }
    messages.sendMessage(
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

  private fun announce(
    sender: CommandSender,
    target: Player,
    modelId: String?,
    label: ac.shard.api.detection.LabelId?,
  ) {
    val initiator = CommandInitiator.of(sender)
    events.publish(BufferResetEventImpl(target.uniqueId, target.name, initiator, modelId, label))
  }

  private fun clear(
    target: Player,
    aiCheck: DetectionState,
    match: (DetectionKey) -> Boolean,
  ): Map<DetectionKey, Double> {
    if (playerDataManager.getPlayer(target)?.buffersRestored != true) aiCheck.blockRestore(match)
    val cleared = aiCheck.clearWhere(match)
    if (configManager.settings.buffer.enabled) {
      val uuid = target.uniqueId
      scheduler.runAsync {
        databaseManager.database.clearAiLabelBuffers(uuid) { match(aiCheck.keyOf(it)) }
      }
    }
    return cleared
  }

  private fun describe(cleared: Map<String, Double>): String {
    val catalog = configManager.labelCatalog
    return cleared.entries
      .sortedByDescending { it.value }
      .joinToString(", ") {
        val name = if (catalog.hidden(it.key)) PlayerInference.NAME else catalog.displayName(it.key)
        "$name ${format(it.value)}"
      }
  }

  private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)

  private fun aiCheck(target: Player): DetectionState? =
    playerDataManager.getPlayer(target)?.detection
}
