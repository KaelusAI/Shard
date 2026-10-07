/*
 * This file is part of GrimAC - https://github.com/GrimAnticheat/Grim
 * Copyright (C) 2021-2026 GrimAC, DefineOutside and contributors
 *
 * GrimAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.packet

import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.impl.Sessions
import ac.shard.api.impl.event.ClientBrandEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.player.ShardPlayer
import ac.shard.utils.ChatUtil
import ac.shard.utils.Message
import ac.shard.utils.Messages
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage
import java.nio.charset.StandardCharsets

class ClientBrandHandler(
  private val configManager: ConfigManager,
  private val alertManager: AlertManager,
  private val messages: Messages,
  private val events: ShardEvents,
  private val sessions: Sessions,
) {
  private val channel by lazy {
    if (PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_13)) {
      "minecraft:brand"
    } else {
      "MC|Brand"
    }
  }

  fun onPacketReceive(player: ShardPlayer, event: PacketReceiveEvent) {
    when (event.packetType) {
      PacketType.Play.Client.PLUGIN_MESSAGE -> {
        val packet = WrapperPlayClientPluginMessage(event)
        handle(player, packet.channelName, packet.data)
      }
      PacketType.Configuration.Client.PLUGIN_MESSAGE -> {
        val packet = WrapperConfigClientPluginMessage(event)
        handle(player, packet.channelName, packet.data)
      }
      else -> Unit
    }
  }

  private fun handle(player: ShardPlayer, channelName: String, data: ByteArray) {
    if (channelName != channel || player.brandReceived) {
      return
    }
    synchronized(player.sessionLock) {
      player.brand = parse(data)
      player.brandReceived = true
      if (player.sessionStarted) {
        events.publish(ClientBrandEventImpl(sessions.of(player), player.brand))
      }
    }

    if (!configManager.settings.clientBrand.isIgnored(player.brand)) {
      alertManager.send(
        messages.getMessage(
          Message.BRAND_NOTIFICATION,
          "player",
          player.name,
          "brand",
          player.brand,
        ),
        AlertType.BRAND,
      )
    }
    val version = player.user.clientVersion
    val hasReachExploit =
      player.brand.contains("forge") &&
        version.isNewerThanOrEquals(ClientVersion.V_1_18_2) &&
        version.isOlderThan(ClientVersion.V_1_19_4)
    if (hasReachExploit && configManager.settings.clientBrand.disconnectBlacklistedForge) {
      player.disconnect(messages.getMessage(Message.BRAND_DISCONNECT_FORGE))
    }
  }

  private fun parse(data: ByteArray): String {
    if (data.size > MAX_BRAND_BYTES || data.isEmpty()) {
      return "invalid (${data.size} bytes)"
    }
    val brand = String(data, 1, data.size - 1, StandardCharsets.UTF_8).replace(" (Velocity)", "")
    return ChatUtil.stripColor(brand) ?: brand
  }

  private companion object {
    const val MAX_BRAND_BYTES = 64
  }
}
