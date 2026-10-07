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
import ac.shard.connect.LinkIntent
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
import ac.shard.utils.Messages
import ac.shard.utils.MiniText
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.parser.standard.StringParser

private const val PERMISSION = "shard.setup"
private const val MILLIS_PER_SECOND = 1000L
private const val FAST_POLL_MINUTES = 15L
private const val FAST_POLL_SECONDS = 5L
private const val SLOW_POLL_SECONDS = 60L
private const val STOP_POLLING_AFTER_MINUTES = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val CONSOLE_NAME = "console"
private const val RETRY_AFTER_SECONDS = 5L
private const val CONSOLE_ONLY_REASON =
  "this server applies results from the console only, so nobody in chat could take it"

@Suppress("TooManyFunctions", "LongParameterList")
internal class SetupCommand(
  private val messages: Messages,
  private val logger: Logger,
  private val runner: SessionRunner,
  private val link: ServerLink,
  private val scheduler: SchedulerService,
  private val configManager: ConfigManager,
  private val server: org.bukkit.Server,
) : ShardCommand {

  private val held = AtomicReference<PanelSessionFlow.Held?>(null)
  private val flow =
    PanelSessionFlow(
      SessionKind.SETUP,
      runner,
      messages,
      scheduler,
      PanelSessionFlow.Replies(
        applied = Message.SETUP_APPLIED,
        rejected = Message.SETUP_REJECTED,
        rolledBack = Message.SETUP_REJECTED,
        nothingToConfirm = Message.SETUP_NOTHING_TO_CONFIRM,
      ),
    )

  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("setup").permission(PERMISSION).handler { open(it) }
    }
    manager.shardCommand {
      literal("setup").literal("status").permission(PERMISSION).handler { status(it) }
    }
    manager.shardCommand {
      literal("setup").literal("cancel").permission(PERMISSION).handler { cancel(it) }
    }
    manager.shardCommand {
      literal("setup").literal("apply").literal("confirm").permission(PERMISSION).handler {
        confirm(it)
      }
    }
    manager.shardCommand {
      literal("setup")
        .literal("apply")
        .required("code", StringParser.stringParser())
        .permission(PERMISSION)
        .handler { claim(it) }
    }
  }

  override fun start() {
    val session = runner.session(SessionKind.SETUP)
    if (session == null) {
      scheduler.runAsync {
        link.resume { step -> onLinkStep(server.consoleSender, null, CONSOLE_NAME, step) }
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
    val actor = if (sender.isTrustedConsole) null else sender.uniqueId
    scheduler.runAsync {
      if (link.isLinked()) {
        if (!sender.isTrustedConsole) {
          messages.sendMessage(native, Message.SETUP_ALREADY_LINKED)
          return@runAsync
        }
        openSession(actor, sender.name, showLink = true)
      } else if (link.begin(LinkIntent.SETUP) { onLinkStep(native, actor, sender.name, it) }) {
        messages.sendMessage(native, Message.SETUP_RESTARTED)
      }
    }
  }

  private fun onLinkStep(native: CommandSender, actor: UUID?, name: String, step: LinkStep) {
    when (step) {
      is LinkStep.NeedsApproval -> {
        messages.sendMessage(
          native,
          messages.getMessage(
            Message.SETUP_LINKING,
            TagResolver.resolver(MiniText.clickUrlTag("link", step.url)),
          ),
        )
        messages.sendMessage(
          native,
          Message.SETUP_LINK_URL,
          "url",
          step.plainUrl,
          "code",
          step.userCode,
        )
      }
      is LinkStep.Linked -> {
        announce(actor) {
          messages.sendMessage(it, Message.SETUP_LINKED, "server", MiniText.escape(step.serverName))
        }
        openSession(actor, name, showLink = false)
      }
      LinkStep.Denied -> linkFailed(actor, "The link was refused in the panel.")
      LinkStep.Expired -> linkFailed(actor, "The link code expired.")
      is LinkStep.Failed -> linkFailed(actor, step.message)
    }
  }

  private fun linkFailed(actor: UUID?, reason: String) {
    announce(actor) { messages.sendMessage(it, Message.SETUP_LINK_FAILED, "reason", reason) }
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
            messages.sendMessage(
              it,
              messages.getMessage(
                Message.SETUP_OPENED,
                TagResolver.resolver(
                  MiniText.clickUrlTag("link", outcome.url),
                  Placeholder.unparsed("code", outcome.userCode),
                ),
              ),
            )
            messages.sendMessage(it, Message.SETUP_URL, "url", outcome.url)
          }
        }
        startPolling(
          actor,
          Instant.now().plusSeconds(STOP_POLLING_AFTER_MINUTES * SECONDS_PER_MINUTE),
        )
      }
      is StartOutcome.Busy -> announce(actor) { messages.sendMessage(it, Message.SETUP_BUSY) }
      is StartOutcome.Error ->
        if (mayRetry) {
          scheduler.runLaterAsync(
            { openSession(actor, name, showLink, mayRetry = false) },
            RETRY_AFTER_SECONDS * MILLIS_PER_SECOND,
          )
        } else {
          announce(actor) {
            messages.sendMessage(it, Message.SETUP_ERROR, "reason", outcome.message)
          }
        }
    }
  }

  private fun startPolling(actor: UUID?, stopAt: Instant) {
    flow.watch(stopAt, ::pollDelay) { outcome ->
      when (outcome) {
        FetchOutcome.Gone -> tell(actor) { messages.sendMessage(it, Message.SETUP_EXPIRED) }
        is FetchOutcome.Ready -> land(actor, outcome)
        else -> Unit
      }
    }
  }

  private fun pollDelay(startedAt: Instant): Long {
    val fast = Duration.between(startedAt, Instant.now()).toMinutes() < FAST_POLL_MINUTES
    return if (fast) FAST_POLL_SECONDS else SLOW_POLL_SECONDS
  }

  private fun land(actor: UUID?, outcome: FetchOutcome.Ready) {
    if (configManager.settings.editorConsoleOnly && actor != null) {
      announce(actor) { messages.sendMessage(it, Message.EDITOR_CONSOLE_ONLY) }
      scheduler.runAsync { runner.refuse(SessionKind.SETUP, outcome.token, CONSOLE_ONLY_REASON) }
      return
    }
    if (outcome.token.needsConfirming) {
      held.set(flow.hold(outcome.token))
      announce(actor) { native ->
        flow.showDiff(native, outcome.rows)
        messages.sendMessage(native, Message.SETUP_CONFIRM)
      }
      return
    }
    commit(actor, outcome.token)
  }

  private fun commit(actor: UUID?, token: PendingApply) {
    flow.commit(token) { send -> announce(actor, send) }
  }

  private fun confirm(context: CommandContext<Sender>) {
    val sender = context.sender()
    val actor = if (sender.isTrustedConsole) null else sender.uniqueId
    flow.confirm(sender.nativeSender, held.getAndSet(null)) { commit(actor, it) }
  }

  private fun claim(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    val actor = if (sender.isTrustedConsole) null else sender.uniqueId
    val code = context.get<String>("code")
    scheduler.runAsync {
      when (val outcome = runner.claim(SessionKind.SETUP, code)) {
        FetchOutcome.NoSession,
        FetchOutcome.Gone -> messages.sendMessage(native, Message.SETUP_EXPIRED)
        FetchOutcome.Waiting -> messages.sendMessage(native, Message.SETUP_WAITING, "minutes", "0")
        is FetchOutcome.Error ->
          messages.sendMessage(native, Message.SETUP_CLAIM_FAILED, "reason", outcome.message)
        is FetchOutcome.Ready -> land(actor, outcome)
      }
    }
  }

  private fun status(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    val session = runner.session(SessionKind.SETUP)
    if (session == null) {
      if (link.pendingIntent() == LinkIntent.SETUP) {
        messages.sendMessage(native, Message.SETUP_AWAITING_LINK)
      } else {
        messages.sendMessage(native, Message.SETUP_NO_SESSION)
      }
    } else {
      val left = Duration.between(Instant.now(), session.deadline).toMinutes().coerceAtLeast(0)
      messages.sendMessage(native, Message.SETUP_WAITING, "minutes", left.toString())
    }
  }

  private fun cancel(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    link.cancel(LinkIntent.SETUP)
    scheduler.runAsync {
      runner.cancel(SessionKind.SETUP)
      messages.sendMessage(native, Message.SETUP_CANCELLED)
    }
  }

  private fun announce(actor: UUID?, send: (org.bukkit.command.CommandSender) -> Unit) {
    send(server.consoleSender)
    tell(actor, send)
  }

  private fun tell(actor: UUID?, send: (org.bukkit.command.CommandSender) -> Unit) {
    val player = actor?.let { server.getPlayer(it) }
    if (player != null) {
      scheduler.runSync { send(player) }
    } else {
      logger.fine("[Setup] the person who started the wizard is offline")
    }
  }
}
