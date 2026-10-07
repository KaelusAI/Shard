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
import ac.shard.command.shardCommand
import ac.shard.config.ConfigManager
import ac.shard.editor.ApplyResult
import ac.shard.editor.SessionKind
import ac.shard.panel.FetchOutcome
import ac.shard.panel.PendingApply
import ac.shard.panel.SessionRunner
import ac.shard.panel.StartOutcome
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.Messages
import ac.shard.utils.MiniText
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.parser.standard.StringParser

private const val PERMISSION = "shard.editor"
private const val APPLY_PERMISSION = "shard.editor.apply"
private const val WATCH_MINUTES = 15L
private const val POLL_SECONDS = 5L
private const val SECONDS_PER_MINUTE = 60L
private const val MAX_BACKUPS_SHOWN = 8

@Suppress("TooManyFunctions")
internal class EditorCommand(
  private val messages: Messages,
  private val runner: SessionRunner,
  private val scheduler: SchedulerService,
  private val configManager: ConfigManager,
) : ShardCommand {

  private val pending = ConcurrentHashMap<UUID, PanelSessionFlow.Held>()
  private val flow =
    PanelSessionFlow(
      SessionKind.EDITOR,
      runner,
      messages,
      scheduler,
      PanelSessionFlow.Replies(
        applied = Message.EDITOR_APPLIED,
        rejected = Message.EDITOR_REJECTED,
        rolledBack = Message.EDITOR_ROLLED_BACK,
        nothingToConfirm = Message.EDITOR_NOTHING_TO_CONFIRM,
      ),
    )

  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("editor").permission(PERMISSION).handler { open(it) }
    }
    manager.shardCommand {
      literal("editor").literal("apply").permission(APPLY_PERMISSION).handler { apply(it) }
    }
    manager.shardCommand {
      literal("editor").literal("apply").literal("confirm").permission(APPLY_PERMISSION).handler {
        confirm(it)
      }
    }
    manager.shardCommand {
      literal("editor").literal("cancel").permission(PERMISSION).handler { cancel(it) }
    }
    manager.shardCommand {
      literal("editor").literal("backups").permission(APPLY_PERMISSION).handler { backups(it) }
    }
    manager.shardCommand {
      literal("editor").literal("undo").permission(APPLY_PERMISSION).handler { context ->
        undo(context, null)
      }
    }
    manager.shardCommand {
      literal("editor")
        .literal("undo")
        .required("stamp", StringParser.stringParser())
        .permission(APPLY_PERMISSION)
        .handler { context -> undo(context, context.get<String>("stamp")) }
    }
  }

  private fun backups(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    if (!context.sender().isTrustedConsole) {
      messages.sendMessage(native, Message.EDITOR_CONSOLE_ONLY)
      return
    }
    scheduler.runAsync {
      val stamps = runner.backups()
      if (stamps.isEmpty()) {
        messages.sendMessage(native, Message.EDITOR_NO_BACKUPS)
      } else {
        messages.sendMessage(
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
    val needsConsole = stamp != null || configManager.settings.editorConsoleOnly
    if (needsConsole && !sender.isTrustedConsole) {
      messages.sendMessage(native, Message.EDITOR_CONSOLE_ONLY)
      return
    }
    scheduler.runAsync {
      val target = stamp ?: runner.backups().firstOrNull()
      if (target == null) {
        messages.sendMessage(native, Message.EDITOR_NO_BACKUPS)
        return@runAsync
      }
      when (val result = runner.restore(target)) {
        is ApplyResult.Applied ->
          messages.sendMessage(native, Message.EDITOR_UNDONE, "stamp", target)
        is ApplyResult.Refused ->
          messages.sendMessage(native, Message.EDITOR_REJECTED, "reason", result.reasons.first())
        is ApplyResult.RolledBack ->
          messages.sendMessage(native, Message.EDITOR_ROLLED_BACK, "reason", result.reason)
      }
    }
  }

  private fun open(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val actor = if (sender.isTrustedConsole) null else sender.uniqueId
    scheduler.runAsync {
      when (val outcome = runner.start(SessionKind.EDITOR, actor, sender.name)) {
        is StartOutcome.Started -> {
          messages.sendMessage(
            native,
            messages.getMessage(
              Message.EDITOR_OPENED,
              TagResolver.resolver(
                MiniText.clickUrlTag("link", outcome.url),
                Placeholder.unparsed("code", outcome.userCode),
              ),
            ),
          )
          messages.sendMessage(native, Message.EDITOR_URL, "url", outcome.url)
          startWatching(sender, native)
        }
        is StartOutcome.Busy -> messages.sendMessage(native, Message.EDITOR_BUSY)
        is StartOutcome.Error ->
          messages.sendMessage(native, Message.EDITOR_ERROR, "reason", outcome.message)
      }
    }
  }

  private fun startWatching(sender: Sender, native: CommandSender) {
    val stopAt = Instant.now().plusSeconds(WATCH_MINUTES * SECONDS_PER_MINUTE)
    flow.watch(stopAt, { POLL_SECONDS }) { outcome ->
      when (outcome) {
        null -> messages.sendMessage(native, Message.EDITOR_WATCH_ENDED)
        is FetchOutcome.Ready -> offer(sender, native, outcome, unattended = true)
        else -> Unit
      }
    }
  }

  private fun apply(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    scheduler.runAsync {
      when (val outcome = runner.fetch(SessionKind.EDITOR)) {
        FetchOutcome.NoSession -> messages.sendMessage(native, Message.EDITOR_NO_SESSION)
        is FetchOutcome.Waiting -> messages.sendMessage(native, Message.EDITOR_WAITING)
        FetchOutcome.Gone -> messages.sendMessage(native, Message.EDITOR_EXPIRED)
        is FetchOutcome.Error ->
          messages.sendMessage(native, Message.EDITOR_ERROR, "reason", outcome.message)
        is FetchOutcome.Ready -> offer(sender, native, outcome)
      }
    }
  }

  @Suppress("ReturnCount")
  private fun offer(
    sender: Sender,
    native: CommandSender,
    outcome: FetchOutcome.Ready,
    unattended: Boolean = false,
  ) {
    flow.showDiff(native, outcome.rows)
    if (configManager.settings.editorConsoleOnly && !sender.isTrustedConsole) {
      messages.sendMessage(native, Message.EDITOR_CONSOLE_ONLY)
      return
    }
    if (!sender.hasPermission(APPLY_PERMISSION)) {
      messages.sendMessage(native, Message.EDITOR_NEEDS_APPLY)
      return
    }
    if (unattended && !outcome.token.needsConfirming) {
      land(native, outcome.token)
      return
    }
    if (outcome.token.needsConfirming) {
      pending[sender.uniqueId] = flow.hold(outcome.token)
      messages.sendMessage(native, Message.EDITOR_CONFIRM)
    } else {
      land(native, outcome.token)
    }
  }

  private fun confirm(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    flow.confirm(native, pending.remove(sender.uniqueId)) { land(native, it) }
  }

  private fun land(native: CommandSender, token: PendingApply) {
    flow.commit(token) { send -> send(native) }
  }

  private fun cancel(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    pending.remove(context.sender().uniqueId)
    scheduler.runAsync {
      runner.cancel(SessionKind.EDITOR)
      messages.sendMessage(native, Message.EDITOR_CANCELLED)
    }
  }
}
