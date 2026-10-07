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
import ac.shard.ai.label.LabelKey
import ac.shard.api.impl.CommandInitiator
import ac.shard.api.impl.event.ShardEvents
import ac.shard.api.impl.event.ViolationLevelResetEventImpl
import ac.shard.command.ShardCommand
import ac.shard.command.TargetResolver
import ac.shard.command.shardCommand
import ac.shard.config.CloudPunishments
import ac.shard.config.ConfigManager
import ac.shard.database.DatabaseManager
import ac.shard.punishment.PunishmentTree
import ac.shard.punishment.cloud.PunishmentSync
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext

@Suppress("LongParameterList")
class PunishCommand(
  private val targets: TargetResolver,
  private val scheduler: SchedulerService,
  private val messages: Messages,
  private val databaseManager: DatabaseManager,
  private val configManager: ConfigManager,
  private val punishmentSync: PunishmentSync,
  private val events: ShardEvents,
) : ShardCommand {
  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      targets.target(literal("punish").permission("shard.punish.manage").literal("reset")).handler {
        reset(it)
      }
    }
    manager.shardCommand {
      literal("punishments").permission("shard.punish.manage").handler { map(it) }
    }
  }

  private fun map(context: CommandContext<Sender>) {
    val tree = configManager.punishmentTree
    val lines = mutableListOf("Punishments:")
    for (model in configManager.streamProfile?.active.orEmpty()) {
      val labels =
        model.labels.filterNot { it in model.legitLabels }.ifEmpty { listOf(LabelKey.UNATTRIBUTED) }
      for (label in labels) {
        val group = tree.route(DetectionKey(model.id, label).address())
        val target =
          when {
            group == null -> "not punished"
            group.actions.isEmpty() -> "${group.key} (no actions, not punished)"
            else -> group.key
          }
        lines += "  ${model.displayTitle} / ${PunishmentTree.labelOf(label)} -> $target"
      }
    }
    tree.problems.forEach { lines += "  ! $it" }
    lines += cloudLines()
    lines.forEach { context.sender().nativeSender.sendMessage(it) }
  }

  private fun cloudLines(): List<String> {
    val status = punishmentSync.status
    val telemetry = configManager.settings.telemetry
    val mode =
      when (telemetry.cloudPunishments) {
        CloudPunishments.OFF ->
          return listOf(
            if (telemetry.enabled) "Panel: not synced (cloud.punishments is off)"
            else "Panel: not synced (telemetry is off)"
          )
        CloudPunishments.UPLOAD -> "shown in the panel, edited only here"
        CloudPunishments.SYNC -> "edited here and in the panel"
      }
    val state = if (status.at == 0L) "not synced yet" else "version ${status.rev}"
    return buildList {
      add("Panel: $mode, $state")
      status.error?.let { add("  Problem: $it") }
      if (status.kept.isNotEmpty()) {
        add("  Changed here and in the panel at once, kept the value from this server:")
        status.kept.forEach { add("    $it") }
      }
    }
  }

  private fun reset(context: CommandContext<Sender>) {
    val sender = context.sender()
    targets.resolve(context) { target ->
      if (!databaseManager.isAvailable) {
        messages.sendMessage(sender.nativeSender, Message.STORAGE_DEGRADED)
      }
      scheduler.runAsync {
        databaseManager.database.resetAllViolationLevels(target.uuid)
        events.publish(
          ViolationLevelResetEventImpl(
            target.uuid,
            target.name,
            CommandInitiator.of(sender.nativeSender),
            null,
          )
        )
        scheduler.runSync {
          messages.sendMessage(
            sender.nativeSender,
            Message.PUNISH_RESET_SUCCESS,
            "player",
            target.name,
          )
        }
      }
    }
  }
}
