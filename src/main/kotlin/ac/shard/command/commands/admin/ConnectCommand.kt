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
import ac.shard.connect.LinkResult
import ac.shard.http.isSecureEndpoint
import ac.shard.panel.LinkStep
import ac.shard.panel.ServerLink
import ac.shard.scheduler.SchedulerService
import ac.shard.sender.Sender
import ac.shard.telemetry.TelemetryService
import ac.shard.utils.Message
import ac.shard.utils.Messages
import ac.shard.utils.MiniText
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.parser.standard.StringParser

@Suppress("TooManyFunctions", "ReturnCount", "LongParameterList")
internal class ConnectCommand(
  private val messages: Messages,
  private val logger: Logger,
  private val configManager: ConfigManager,
  private val scheduler: SchedulerService,
  private val telemetryService: TelemetryService,
  private val link: ServerLink,
  private val server: org.bukkit.Server,
) : ShardCommand {

  private val pendingDisconnect = ConcurrentHashMap<UUID, Long>()

  override fun register(manager: CommandManager<Sender>) {
    manager.shardCommand {
      literal("connect")
        .optional("code", StringParser.stringParser())
        .permission(PERMISSION)
        .handler { connect(it) }
    }
    manager.shardCommand {
      literal("disconnect").permission(PERMISSION).handler { disconnect(it) }
    }
    manager.shardCommand {
      literal("disconnect").literal("confirm").permission(PERMISSION).handler {
        disconnectConfirm(it)
      }
    }
    manager.shardCommand {
      literal("connect").literal("status").permission(PERMISSION).handler { status(it) }
    }
  }

  private fun connect(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    if (!sender.isTrustedConsole && link.isLinked()) {
      messages.sendMessage(native, Message.CONNECT_CONSOLE_ONLY)
      return
    }
    if (!checkPanelUrl(native)) {
      return
    }
    val recipient = Recipient(sender.uniqueId, sender.isConsole)
    val code = context.getOrDefault("code", "")
    if (code.isNotBlank()) {
      link.redeem(code) { onRedeemed(recipient, it) }
      return
    }
    if (link.begin(LinkIntent.CONNECT) { onStep(recipient, it) }) {
      messages.sendMessage(native, Message.CONNECT_RESTARTED)
    }
  }

  private fun onStep(recipient: Recipient, step: LinkStep) {
    notify(recipient) {
      when (step) {
        is LinkStep.NeedsApproval -> sendStartMessages(it, step.userCode)
        is LinkStep.Linked ->
          messages.sendMessage(
            it,
            Message.CONNECT_SUCCESS,
            "server",
            MiniText.escape(step.serverName),
          )
        LinkStep.Denied -> messages.sendMessage(it, Message.CONNECT_DENIED)
        LinkStep.Expired -> messages.sendMessage(it, Message.CONNECT_EXPIRED)
        is LinkStep.Failed ->
          messages.sendMessage(it, Message.CONNECT_ERROR, "reason", step.message)
      }
    }
  }

  private fun onRedeemed(recipient: Recipient, result: LinkResult) {
    notify(recipient) {
      when (result) {
        is LinkResult.Linked ->
          messages.sendMessage(
            it,
            Message.CONNECT_LINK_SUCCESS,
            "server",
            MiniText.escape(result.credentials.serverName ?: "your server"),
          )
        LinkResult.InvalidOrExpired -> messages.sendMessage(it, Message.CONNECT_LINK_INVALID)
        is LinkResult.Error ->
          messages.sendMessage(it, Message.CONNECT_ERROR, "reason", result.message)
      }
    }
  }

  private fun disconnect(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    if (!sender.isTrustedConsole) {
      messages.sendMessage(native, Message.CONNECT_CONSOLE_ONLY)
      return
    }
    if (!link.isLinked()) {
      messages.sendMessage(native, Message.CONNECT_DISCONNECT_NOTHING)
      return
    }
    pendingDisconnect[sender.uniqueId] = Instant.now().epochSecond + CONFIRM_WINDOW_SECONDS
    messages.sendMessage(native, Message.CONNECT_DISCONNECT_CONFIRM)
  }

  private fun disconnectConfirm(context: CommandContext<Sender>) {
    val sender = context.sender()
    val native = sender.nativeSender
    if (!sender.isTrustedConsole) {
      messages.sendMessage(native, Message.CONNECT_CONSOLE_ONLY)
      return
    }
    val deadline = pendingDisconnect.remove(sender.uniqueId)
    if (deadline == null || Instant.now().epochSecond > deadline) {
      messages.sendMessage(native, Message.CONNECT_DISCONNECT_NO_PENDING)
      return
    }
    val recipient = Recipient(sender.uniqueId, sender.isConsole)
    val canRevoke = configManager.settings.panelUrl.let { it.isNotBlank() && isSecureEndpoint(it) }
    val unlinking =
      link.unlink(canRevoke) { revoked ->
        if (!revoked) {
          logger.warning(
            "[Connect] Panel revoke not confirmed; local credentials were cleared anyway. " +
              "Revoke this server in the panel manually."
          )
        }
        val message =
          if (revoked) Message.CONNECT_DISCONNECT_SUCCESS else Message.CONNECT_DISCONNECT_LOCAL_ONLY
        notify(recipient) { messages.sendMessage(it, message) }
      }
    if (!unlinking) messages.sendMessage(native, Message.CONNECT_DISCONNECT_NOTHING)
  }

  private fun status(context: CommandContext<Sender>) {
    val native = context.sender().nativeSender
    val credentials = link.credentials()
    if (credentials == null) {
      messages.sendMessage(native, Message.CONNECT_STATUS_NOT_LINKED)
      return
    }
    messages.sendMessage(native, Message.CONNECT_STATUS_HEADER)
    messages.sendMessage(
      native,
      Message.CONNECT_STATUS_LINKED,
      "server",
      MiniText.escape(credentials.serverName ?: credentials.serverId ?: "unknown"),
    )
    messages.sendMessage(
      native,
      Message.CONNECT_STATUS_KEY,
      "key",
      maskKey(credentials.secretKey),
    )
    messages.sendMessage(
      native,
      Message.CONNECT_STATUS_SERVER_URL,
      "url",
      configManager.settings.ai.url,
    )
    if (configManager.streamProfile != null) {
      messages.sendMessage(
        native,
        Message.CONNECT_STATUS_MODEL,
        "model",
        configManager.modelTitle(),
      )
    }
    val cached = telemetryService.quotaSnapshot
    if (cached != null) {
      messages.sendMessage(
        native,
        Message.CONNECT_STATUS_QUOTA,
        "used",
        cached.usedPercent.toString(),
      )
    } else {
      val sender = context.sender()
      val recipient = Recipient(sender.uniqueId, sender.isConsole)
      scheduler.runAsync {
        val pct = telemetryService.fetchQuota()
        notify(recipient) {
          if (pct != null) {
            messages.sendMessage(it, Message.CONNECT_STATUS_QUOTA, "used", pct.toString())
          } else {
            messages.sendMessage(it, Message.CONNECT_STATUS_QUOTA_UNAVAILABLE)
          }
        }
      }
    }
  }

  private fun checkPanelUrl(sender: CommandSender): Boolean {
    val url = configManager.settings.panelUrl
    if (url.isBlank()) {
      messages.sendMessage(sender, Message.CONNECT_DISABLED)
      return false
    }
    if (!isSecureEndpoint(url)) {
      messages.sendMessage(sender, Message.CONNECT_INSECURE_URL)
      return false
    }
    return true
  }

  private fun sendStartMessages(sender: CommandSender, userCode: String) {
    val base = configManager.settings.panelUrl.trim().trimEnd('/')
    val verifyUrl = "$base/connect"
    val resolver =
      TagResolver.resolver(
        MiniText.clickUrlTag("link", "$verifyUrl?code=$userCode"),
        Placeholder.unparsed("code", userCode),
      )
    messages.sendMessage(sender, messages.getMessage(Message.CONNECT_START, resolver))
    messages.sendMessage(sender, Message.CONNECT_URL, "url", verifyUrl, "code", userCode)
    messages.sendMessage(sender, Message.CONNECT_WAITING)
  }

  private fun notify(recipient: Recipient, action: (CommandSender) -> Unit) {
    scheduler.runSync {
      val target: CommandSender? =
        if (recipient.isConsole) server.consoleSender else server.getPlayer(recipient.uuid)
      if (target != null) {
        action(target)
      } else {
        logger.info("[Connect] Recipient offline; a connect message was not delivered.")
      }
    }
  }

  private fun maskKey(key: String): String =
    if (key.length <= MASK_VISIBLE) "•".repeat(key.length)
    else "••••••••" + key.takeLast(MASK_VISIBLE)

  private data class Recipient(val uuid: UUID, val isConsole: Boolean)

  private companion object {
    const val PERMISSION = "shard.connect"
    const val MASK_VISIBLE = 4
    const val CONFIRM_WINDOW_SECONDS = 30L
  }
}
