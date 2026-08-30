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

import ac.shard.Shard
import ac.shard.command.ShardCommand
import ac.shard.editor.ApplyResult
import ac.shard.editor.DiffRow
import ac.shard.editor.DiffWeight
import ac.shard.editor.EditorDiff
import ac.shard.editor.SessionKind
import ac.shard.panel.FetchOutcome
import ac.shard.panel.LinkStep
import ac.shard.panel.PendingApply
import ac.shard.panel.ServerLink
import ac.shard.panel.SessionRunner
import ac.shard.panel.StartOutcome
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.kotlin.extension.buildAndRegister
import org.incendo.cloud.parser.standard.StringParser

private const val PERMISSION = "shards.setup"
private const val MILLIS_PER_SECOND = 1000L
private const val FAST_POLL_MINUTES = 15L
private const val FAST_POLL_SECONDS = 5L
private const val SLOW_POLL_SECONDS = 60L
private const val STOP_POLLING_AFTER_MINUTES = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val CONFIRM_WINDOW_SECONDS = 600L
private const val MAX_DIFF_ROWS = 20
private const val CONSOLE_NAME = "console"
private const val RETRY_AFTER_SECONDS = 5L
private const val CONSOLE_ONLY_REASON =
  "this server applies results from the console only, so nobody in chat could take it"

@Suppress("TooManyFunctions")
internal class SetupCommand(
  private val plugin: Shard,
  private val runner: SessionRunner,
  private val link: ServerLink,
  private val scheduler: SchedulerService,
  private val configManager: ac.shard.config.ConfigManager,
) : ShardCommand {

  private val polling = AtomicBoolean(false)
  private val linking = AtomicBoolean(false)
  private val held = AtomicReference<Confirmable?>(null)

  override fun register(manager: CommandManager<Sender>) {
    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("setup").permission(PERMISSION).handler(this@SetupCommand::open)
    }
    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("setup").literal("status").permission(PERMISSION).handler(this@SetupCommand::status)
    }
    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("setup").literal("cancel").permission(PERMISSION).handler(this@SetupCommand::cancel)
    }
    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("setup")
        .literal("apply")
        .literal("confirm")
        .permission(PERMISSION)
        .handler(this@SetupCommand::confirm)
    }
    manager.buildAndRegister("shards", aliases = arrayOf("shardsac", "shrd")) {
      literal("setup")
        .literal("apply")
        .required("code", StringParser.stringParser())
        .permission(PERMISSION)
        .handler(this@SetupCommand::claim)
    }
    resumeOnEnable()
  }

  private fun resumeOnEnable() {
    val session = runner.session(SessionKind.SETUP)
    if (session == null) {
      scheduler.runAsync {
        val resumed = link.resume { step ->
          onLinkStep(Bukkit.getConsoleSender(), null, CONSOLE_NAME, step)
        }
        if (resumed) linking.set(true)
      }
      return
    }
    if (Instant.now().isBefore(session.deadline)) {
      startPolling(
        session.openedBy,
        Instant.now().plusSeconds(STOP_POLLING_AFTER_MINUTES * SECONDS_PER_MINUTE),
      )
    }
  }

  private fun open(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val actor = if (sender.isConsole) null else sender.uniqueId
    scheduler.runAsync {
      if (link.isLinked()) {
        if (!sender.isConsole) {
          MessageUtil.sendMessage(native, Message.SETUP_ALREADY_LINKED)
          return@runAsync
        }
        openSession(actor, sender.name, showLink = true)
      } else {
        if (link.waiting()) {
          link.forget()
          MessageUtil.sendMessage(native, Message.SETUP_RESTARTED)
        }
        linking.set(true)
        link.begin { step -> onLinkStep(native, actor, sender.name, step) }
      }
    }
  }

  private fun onLinkStep(native: CommandSender, actor: UUID?, name: String, step: LinkStep) {
    when (step) {
      is LinkStep.NeedsApproval -> {
        MessageUtil.sendMessage(
          native,
          MessageUtil.getMessage(
            Message.SETUP_LINKING,
            TagResolver.resolver(MessageUtil.clickUrlTag("link", step.url)),
          ),
        )
        MessageUtil.sendMessage(
          native,
          Message.SETUP_LINK_URL,
          "url",
          step.plainUrl,
          "code",
          step.userCode,
        )
      }
      is LinkStep.Linked -> {
        linking.set(false)
        announce(actor) {
          MessageUtil.sendMessage(it, Message.SETUP_LINKED, "server", step.serverName)
        }
        openSession(actor, name, showLink = false)
      }
      is LinkStep.Failed -> {
        linking.set(false)
        announce(actor) {
          MessageUtil.sendMessage(it, Message.SETUP_LINK_FAILED, "reason", step.message)
        }
      }
    }
  }

  private fun openSession(
    actor: UUID?,
    name: String,
    showLink: Boolean,
    mayRetry: Boolean = true,
  ) {
    when (val outcome = runner.start(SessionKind.SETUP, actor, name)) {
      is StartOutcome.Started -> {
        if (showLink) {
          announce(actor) {
            MessageUtil.sendMessage(
              it,
              MessageUtil.getMessage(
                Message.SETUP_OPENED,
                TagResolver.resolver(
                  MessageUtil.clickUrlTag("link", outcome.url),
                  Placeholder.unparsed("code", outcome.userCode),
                ),
              ),
            )
            MessageUtil.sendMessage(it, Message.SETUP_URL, "url", outcome.url)
          }
        }
        startPolling(
          actor,
          Instant.now().plusSeconds(STOP_POLLING_AFTER_MINUTES * SECONDS_PER_MINUTE),
        )
      }
      is StartOutcome.Busy -> announce(actor) { MessageUtil.sendMessage(it, Message.SETUP_BUSY) }
      is StartOutcome.Error ->
        if (mayRetry) {
          scheduler.runLaterAsync(
            { openSession(actor, name, showLink, mayRetry = false) },
            RETRY_AFTER_SECONDS * MILLIS_PER_SECOND,
          )
        } else {
          announce(actor) {
            MessageUtil.sendMessage(it, Message.SETUP_ERROR, "reason", outcome.message)
          }
        }
    }
  }

  private fun startPolling(actor: UUID?, stopAt: Instant) {
    if (polling.compareAndSet(false, true)) {
      scheduleNext(actor, stopAt, Instant.now())
    }
  }

  private fun scheduleNext(actor: UUID?, stopAt: Instant, startedAt: Instant) {
    val fast = Duration.between(startedAt, Instant.now()).toMinutes() < FAST_POLL_MINUTES
    val seconds = if (fast) FAST_POLL_SECONDS else SLOW_POLL_SECONDS
    scheduler.runLaterAsync({ pollOnce(actor, stopAt, startedAt) }, seconds * MILLIS_PER_SECOND)
  }

  private fun pollOnce(actor: UUID?, stopAt: Instant, startedAt: Instant) {
    if (Instant.now().isAfter(stopAt)) {
      polling.set(false)
      return
    }
    when (val outcome = runner.fetch(SessionKind.SETUP)) {
      FetchOutcome.NoSession -> polling.set(false)
      FetchOutcome.Waiting -> scheduleNext(actor, stopAt, startedAt)
      FetchOutcome.Gone -> {
        polling.set(false)
        tell(actor) { MessageUtil.sendMessage(it, Message.SETUP_EXPIRED) }
      }
      is FetchOutcome.Error -> scheduleNext(actor, stopAt, startedAt)
      is FetchOutcome.Ready -> {
        polling.set(false)
        land(actor, outcome)
      }
    }
  }

  private fun land(actor: UUID?, outcome: FetchOutcome.Ready) {
    val needsConsole =
      configManager.editorConsoleOnly ||
        (outcome.token.carriesCommands && configManager.editorConsoleForCommands)
    if (needsConsole && actor != null) {
      announce(actor) { MessageUtil.sendMessage(it, Message.EDITOR_CONSOLE_ONLY) }
      scheduler.runAsync { runner.refuse(SessionKind.SETUP, outcome.token, CONSOLE_ONLY_REASON) }
      return
    }
    if (outcome.token.needsConfirming) {
      held.set(Confirmable(outcome.token, Instant.now().plusSeconds(CONFIRM_WINDOW_SECONDS)))
      runner.holding(outcome.token, CONFIRM_WINDOW_SECONDS)
      announce(actor) { native ->
        showDiff(native, outcome.rows)
        MessageUtil.sendMessage(native, Message.SETUP_CONFIRM)
      }
      return
    }
    commit(actor, outcome.token)
  }

  private fun commit(actor: UUID?, token: PendingApply) {
    when (val result = runner.commit(SessionKind.SETUP, token)) {
      is ApplyResult.Applied ->
        announce(actor) {
          MessageUtil.sendMessage(
            it,
            Message.SETUP_APPLIED,
            "changed",
            result.count.toString(),
            "stamp",
            result.stamp,
          )
        }
      is ApplyResult.Refused ->
        announce(actor) {
          MessageUtil.sendMessage(it, Message.SETUP_REJECTED, "reason", result.reasons.first())
        }
      is ApplyResult.RolledBack ->
        announce(actor) {
          MessageUtil.sendMessage(it, Message.SETUP_REJECTED, "reason", result.reason)
        }
    }
  }

  private fun confirm(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val actor = if (sender.isConsole) null else sender.uniqueId
    val waiting = held.getAndSet(null)
    when {
      waiting == null -> MessageUtil.sendMessage(native, Message.SETUP_NOTHING_TO_CONFIRM)
      Instant.now().isAfter(waiting.until) -> {
        MessageUtil.sendMessage(native, Message.SETUP_NOTHING_TO_CONFIRM)
        scheduler.runAsync { runner.abandon(SessionKind.SETUP, waiting.token) }
      }
      else -> scheduler.runAsync { commit(actor, waiting.token) }
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

  private class Confirmable(val token: PendingApply, val until: Instant)

  private fun claim(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val actor = if (sender.isConsole) null else sender.uniqueId
    val code = context.get<String>("code")
    scheduler.runAsync {
      when (val outcome = runner.claim(SessionKind.SETUP, code)) {
        FetchOutcome.NoSession,
        FetchOutcome.Gone -> MessageUtil.sendMessage(native, Message.SETUP_EXPIRED)
        FetchOutcome.Waiting ->
          MessageUtil.sendMessage(native, Message.SETUP_WAITING, "minutes", "0")
        is FetchOutcome.Error ->
          MessageUtil.sendMessage(native, Message.SETUP_CLAIM_FAILED, "reason", outcome.message)
        is FetchOutcome.Ready -> land(actor, outcome)
      }
    }
  }

  private fun status(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    val session = runner.session(SessionKind.SETUP)
    if (session == null) {
      if (linking.get()) {
        MessageUtil.sendMessage(native, Message.SETUP_AWAITING_LINK)
      } else {
        MessageUtil.sendMessage(native, Message.SETUP_NO_SESSION)
      }
    } else {
      val left = Duration.between(Instant.now(), session.deadline).toMinutes().coerceAtLeast(0)
      MessageUtil.sendMessage(native, Message.SETUP_WAITING, "minutes", left.toString())
    }
  }

  private fun cancel(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    polling.set(false)
    linking.set(false)
    scheduler.runAsync {
      link.forget()
      runner.cancel(SessionKind.SETUP)
      MessageUtil.sendMessage(native, Message.SETUP_CANCELLED)
    }
  }

  private fun announce(actor: UUID?, send: (org.bukkit.command.CommandSender) -> Unit) {
    send(Bukkit.getConsoleSender())
    tell(actor, send)
  }

  private fun tell(actor: UUID?, send: (org.bukkit.command.CommandSender) -> Unit) {
    val player = actor?.let { Bukkit.getPlayer(it) }
    if (player != null) {
      scheduler.runSync { send(player) }
    } else {
      plugin.logger.fine("[Setup] the person who started the wizard is offline")
    }
  }
}
