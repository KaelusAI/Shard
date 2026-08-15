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

import ac.shard.command.ShardCommand
import ac.shard.editor.ApplyResult
import ac.shard.editor.DiffRow
import ac.shard.editor.DiffWeight
import ac.shard.editor.EditorDiff
import ac.shard.editor.SessionKind
import ac.shard.panel.FetchOutcome
import ac.shard.panel.PendingApply
import ac.shard.panel.SessionRunner
import ac.shard.panel.StartOutcome
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.parser.standard.StringParser

private const val PERMISSION = "shard.editor"
private const val APPLY_PERMISSION = "shard.editor.apply"
private const val CONFIRM_WINDOW_SECONDS = 30L
private const val MAX_DIFF_ROWS = 20
private const val WATCH_MINUTES = 15L
private const val POLL_SECONDS = 5L
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MAX_BACKUPS_SHOWN = 8

@Suppress("TooManyFunctions")
internal class EditorCommand(
  private val runner: SessionRunner,
  private val scheduler: SchedulerService,
  private val configManager: ac.shard.config.ConfigManager,
) : ShardCommand {

  private val pending = ConcurrentHashMap<UUID, Confirmable>()
  private val watching = AtomicBoolean(false)

  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor").permission(PERMISSION).handler(this@EditorCommand::open)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor")
        .literal("apply")
        .permission(APPLY_PERMISSION)
        .handler(this@EditorCommand::apply)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor")
        .literal("apply")
        .literal("confirm")
        .permission(APPLY_PERMISSION)
        .handler(this@EditorCommand::confirm)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor").literal("cancel").permission(PERMISSION).handler(this@EditorCommand::cancel)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor")
        .literal("backups")
        .permission(APPLY_PERMISSION)
        .handler(this@EditorCommand::backups)
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor").literal("undo").permission(APPLY_PERMISSION).handler { context ->
        undo(context, null)
      }
    }
    manager.buildAndRegister("shard", aliases = arrayOf("shardac", "sloth", "slothac")) {
      literal("editor")
        .literal("undo")
        .required("stamp", StringParser.stringParser())
        .permission(APPLY_PERMISSION)
        .handler { context -> undo(context, context.get<String>("stamp")) }
    }
  }

  private fun backups(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    if (!context.sender().isConsole) {
      MessageUtil.sendMessage(native, Message.EDITOR_CONSOLE_ONLY)
      return
    }
    scheduler.runAsync {
      val stamps = runner.backups()
      if (stamps.isEmpty()) {
        MessageUtil.sendMessage(native, Message.EDITOR_NO_BACKUPS)
      } else {
        MessageUtil.sendMessage(
          native,
          Message.EDITOR_BACKUPS,
          "stamps",
          stamps.take(MAX_BACKUPS_SHOWN).joinToString(", "),
        )
      }
    }
  }

  private fun undo(context: CommandContext<Sender>, stamp: String?) {
    val sender = context.sender()
    val native = sender.nativeSender
    val needsConsole = stamp != null || configManager.editorConsoleOnly
    if (needsConsole && !sender.isConsole) {
      MessageUtil.sendMessage(native, Message.EDITOR_CONSOLE_ONLY)
      return
    }
    scheduler.runAsync {
      val target = stamp ?: runner.backups().firstOrNull()
      if (target == null) {
        MessageUtil.sendMessage(native, Message.EDITOR_NO_BACKUPS)
        return@runAsync
      }
      when (val result = runner.restore(target)) {
        is ApplyResult.Applied ->
          MessageUtil.sendMessage(native, Message.EDITOR_UNDONE, "stamp", target)
        is ApplyResult.Refused ->
          MessageUtil.sendMessage(native, Message.EDITOR_REJECTED, "reason", result.reasons.first())
        is ApplyResult.RolledBack ->
          MessageUtil.sendMessage(native, Message.EDITOR_ROLLED_BACK, "reason", result.reason)
      }
    }
  }

  private fun open(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val actor = if (sender.isConsole) null else sender.uniqueId
    scheduler.runAsync {
      when (val outcome = runner.start(SessionKind.EDITOR, actor, sender.name)) {
        is StartOutcome.Started -> {
          MessageUtil.sendMessage(
            native,
            MessageUtil.getMessage(
              Message.EDITOR_OPENED,
              TagResolver.resolver(
                MessageUtil.clickUrlTag("link", outcome.url),
                Placeholder.unparsed("code", outcome.userCode),
              ),
            ),
          )
          MessageUtil.sendMessage(native, Message.EDITOR_URL, "url", outcome.url)
          startWatching(sender, native)
        }
        is StartOutcome.Busy -> MessageUtil.sendMessage(native, Message.EDITOR_BUSY)
        is StartOutcome.Error ->
          MessageUtil.sendMessage(native, Message.EDITOR_ERROR, "reason", outcome.message)
      }
    }
  }

  private fun startWatching(sender: Sender, native: CommandSender) {
    if (!watching.compareAndSet(false, true)) return
    val stopAt = Instant.now().plusSeconds(WATCH_MINUTES * SECONDS_PER_MINUTE)
    scheduleWatch(sender, native, stopAt)
  }

  private fun scheduleWatch(sender: Sender, native: CommandSender, stopAt: Instant) {
    scheduler.runLaterAsync({ watchOnce(sender, native, stopAt) }, POLL_SECONDS * MILLIS_PER_SECOND)
  }

  private fun watchOnce(sender: Sender, native: CommandSender, stopAt: Instant) {
    if (Instant.now().isAfter(stopAt)) {
      watching.set(false)
      MessageUtil.sendMessage(native, Message.EDITOR_WATCH_ENDED)
      return
    }
    when (val outcome = runner.fetch(SessionKind.EDITOR)) {
      is FetchOutcome.Waiting -> scheduleWatch(sender, native, stopAt)
      is FetchOutcome.Error -> scheduleWatch(sender, native, stopAt)
      FetchOutcome.NoSession,
      FetchOutcome.Gone -> watching.set(false)
      is FetchOutcome.Ready -> {
        watching.set(false)
        offer(sender, native, outcome, unattended = true)
      }
    }
  }

  private fun apply(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    scheduler.runAsync {
      when (val outcome = runner.fetch(SessionKind.EDITOR)) {
        FetchOutcome.NoSession -> MessageUtil.sendMessage(native, Message.EDITOR_NO_SESSION)
        is FetchOutcome.Waiting -> MessageUtil.sendMessage(native, Message.EDITOR_WAITING)
        FetchOutcome.Gone -> MessageUtil.sendMessage(native, Message.EDITOR_EXPIRED)
        is FetchOutcome.Error ->
          MessageUtil.sendMessage(native, Message.EDITOR_ERROR, "reason", outcome.message)
        is FetchOutcome.Ready -> offer(sender, native, outcome)
      }
    }
  }

  private fun offer(
    sender: Sender,
    native: CommandSender,
    outcome: FetchOutcome.Ready,
    unattended: Boolean = false,
  ) {
    showDiff(native, outcome.rows)
    val needsConsole =
      configManager.editorConsoleOnly ||
        (outcome.token.carriesCommands && configManager.editorConsoleForCommands)
    if (needsConsole && !sender.isConsole) {
      MessageUtil.sendMessage(native, Message.EDITOR_CONSOLE_ONLY)
      return
    }
    if (unattended && !outcome.token.needsConfirming) {
      land(native, outcome.token)
      return
    }
    if (outcome.token.needsConfirming) {
      pending[sender.uniqueId] =
        Confirmable(outcome.token, Instant.now().plusSeconds(CONFIRM_WINDOW_SECONDS))
      runner.holding(outcome.token, CONFIRM_WINDOW_SECONDS)
      MessageUtil.sendMessage(native, Message.EDITOR_CONFIRM)
    } else {
      land(native, outcome.token)
    }
  }

  private fun confirm(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val held = pending.remove(sender.uniqueId)
    when {
      held == null -> MessageUtil.sendMessage(native, Message.EDITOR_NOTHING_TO_CONFIRM)
      Instant.now().isAfter(held.until) -> {
        MessageUtil.sendMessage(native, Message.EDITOR_NOTHING_TO_CONFIRM)
        scheduler.runAsync { runner.abandon(SessionKind.EDITOR, held.token) }
      }
      else -> scheduler.runAsync { land(native, held.token) }
    }
  }

  private fun land(native: CommandSender, token: PendingApply) {
    when (val result = runner.commit(SessionKind.EDITOR, token)) {
      is ApplyResult.Applied ->
        MessageUtil.sendMessage(
          native,
          Message.EDITOR_APPLIED,
          "changed",
          result.count.toString(),
          "stamp",
          result.stamp,
        )
      is ApplyResult.Refused ->
        MessageUtil.sendMessage(
          native,
          Message.EDITOR_REJECTED,
          "reason",
          result.reasons.first(),
        )
      is ApplyResult.RolledBack ->
        MessageUtil.sendMessage(native, Message.EDITOR_ROLLED_BACK, "reason", result.reason)
    }
  }

  private fun showDiff(native: CommandSender, rows: List<DiffRow>) {
    MessageUtil.sendMessage(native, Message.EDITOR_DIFF_HEADER, "count", rows.size.toString())
    val shown = EditorDiff.visible(rows, MAX_DIFF_ROWS)
    shown.forEach { row ->
      MessageUtil.sendMessage(
        native,
        Message.EDITOR_DIFF_LINE,
        "sign",
        if (row.weight == DiffWeight.NOTABLE) "!" else "•",
        "key",
        row.key,
        "old",
        row.before.ifBlank { "-" },
        "new",
        row.after,
      )
    }
    if (rows.size > shown.size) {
      MessageUtil.sendMessage(
        native,
        Message.EDITOR_DIFF_MORE,
        "count",
        (rows.size - shown.size).toString(),
      )
    }
  }

  private fun cancel(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    pending.remove(context.sender().uniqueId)
    scheduler.runAsync {
      runner.cancel(SessionKind.EDITOR)
      MessageUtil.sendMessage(native, Message.EDITOR_CANCELLED)
    }
  }

  private class Confirmable(val token: PendingApply, val until: Instant)
}
