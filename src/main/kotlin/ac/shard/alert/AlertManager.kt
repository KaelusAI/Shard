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
package ac.shard.alert

import ac.shard.api.Initiator
import ac.shard.api.event.alert.AlertEvent
import ac.shard.api.impl.ShardInitiator
import ac.shard.api.impl.event.AlertEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.utils.Message
import ac.shard.utils.Messages
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

@Suppress("TooManyFunctions", "LongParameterList")
class AlertManager(
  private val messages: Messages,
  private val configManager: ConfigManager,
  private val localeManager: LocaleManager,
  private val adventure: BukkitAudiences,
  private val network: NetworkPublisher,
  private val events: ShardEvents,
  private val server: Server,
) {
  private val playersWithAlerts: MutableMap<AlertType, MutableSet<UUID>> =
    EnumMap(AlertType::class.java)
  private val consoleAlertsEnabled: MutableSet<AlertType> = EnumSet.allOf(AlertType::class.java)

  private var logToConsole = true

  var alertFormat: String = ""
    private set

  var brandAlertFormat: String = ""
    private set

  init {
    for (type in AlertType.values()) {
      playersWithAlerts[type] = CopyOnWriteArraySet()
    }
    reload()
    network.onRemote(::deliverRemote)
  }

  fun reload() {
    logToConsole = configManager.config.getBoolean("alerts.print-to-console", true)
    alertFormat = localeManager.getRawMessage(Message.ALERTS_FORMAT)
    brandAlertFormat = localeManager.getRawMessage(Message.BRAND_NOTIFICATION)
  }

  fun toggle(player: Player, type: AlertType, silent: Boolean) {
    val playersSet = playersWithAlerts.getValue(type)
    val uuid = player.uniqueId

    if (playersSet.contains(uuid)) {
      playersSet.remove(uuid)
      if (!silent) {
        adventure(player).sendMessage(messages.getMessage(type.disabledMessage))
      }
    } else {
      playersSet.add(uuid)
      if (!silent) {
        adventure(player).sendMessage(messages.getMessage(type.enabledMessage))
      }
    }
  }

  fun setEnabled(player: Player, type: AlertType, enabled: Boolean) {
    val playersSet = playersWithAlerts.getValue(type)
    if (enabled && player.isOnline) playersSet.add(player.uniqueId)
    else playersSet.remove(player.uniqueId)
  }

  fun send(
    component: Component,
    type: AlertType,
    subject: UUID? = null,
    initiator: Initiator = ShardInitiator,
    toNetwork: Boolean = true,
  ) {
    if (vetoed(component, type, subject, network.name, false, initiator)) return
    deliver(component, type)
    if (toNetwork) network.publish(type, component)
  }

  private fun deliverRemote(component: Component, type: AlertType, server: String) {
    if (vetoed(component, type, null, server, true, null)) return
    deliver(component, type)
  }

  @Suppress("LongParameterList")
  private fun vetoed(
    component: Component,
    type: AlertType,
    subject: UUID?,
    server: String,
    remote: Boolean,
    initiator: Initiator?,
  ): Boolean {
    if (!events.wants(AlertEvent::class.java)) return false
    val text = PlainTextComponentSerializer.plainText().serialize(component)
    return events
      .fire(AlertEventImpl(type.api, subject, text, server, remote, initiator))
      .isCancelled
  }

  fun deliver(component: Component, type: AlertType) {
    val playersSet = playersWithAlerts.getValue(type)
    val permission = type.permission

    for (uuid in playersSet) {
      val player = server.getPlayer(uuid)
      if (player != null && player.hasPermission(permission)) {
        adventure(player).sendMessage(component)
      }
    }

    if (logToConsole && consoleAlertsEnabled.contains(type)) {
      adventure(server.consoleSender).sendMessage(component)
    }
  }

  fun hasAlertsEnabled(player: Player, type: AlertType): Boolean {
    return playersWithAlerts.getValue(type).contains(player.uniqueId)
  }

  fun isConsoleAlertsEnabled(type: AlertType): Boolean {
    return consoleAlertsEnabled.contains(type)
  }

  fun toggleConsoleAlerts(type: AlertType) {
    if (consoleAlertsEnabled.contains(type)) {
      consoleAlertsEnabled.remove(type)
    } else {
      consoleAlertsEnabled.add(type)
    }
  }

  fun onJoin(player: Player) {
    for (type in AlertType.entries) {
      if (
        player.hasPermission(type.permission) &&
          player.hasPermission("${type.permission}.enable-on-join") &&
          !hasAlertsEnabled(player, type)
      ) {
        toggle(player, type, true)
      }
    }
  }

  fun handlePlayerQuit(player: Player) {
    val uuid = player.uniqueId
    for (players in playersWithAlerts.values) {
      players.remove(uuid)
    }
  }

  private fun adventure(player: Player): Audience {
    return adventure.player(player)
  }

  private fun adventure(sender: CommandSender): Audience {
    return adventure.sender(sender)
  }
}
