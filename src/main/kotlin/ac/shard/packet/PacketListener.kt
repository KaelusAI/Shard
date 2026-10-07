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
package ac.shard.packet

import ac.shard.player.PlayerDataManager
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.event.UserLoginEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import org.bukkit.entity.Player

class PacketListener(
  private val playerDataManager: PlayerDataManager,
  private val clientBrand: ClientBrandHandler,
  private val flying: FlyingProcessor,
  clientActions: ClientActions,
) : PacketListenerAbstract() {
  private val receiveRoutes = routeTable(receiveRoutes(clientActions))
  private val sendRoutes = routeTable(SEND_ROUTES)

  override fun onUserLogin(event: UserLoginEvent) {
    val user: User = event.user ?: return
    val player: Player = event.getPlayer() ?: return
    playerDataManager.handleUserLogin(user, player)
  }

  override fun onUserDisconnect(event: UserDisconnectEvent) {
    playerDataManager.handleUserDisconnect(event.user)
  }

  override fun onPacketReceive(event: PacketReceiveEvent) {
    val shardPlayer = playerDataManager.getPlayer(event.user) ?: return

    if (!shardPlayer.isAttached) {
      clientBrand.onPacketReceive(shardPlayer, event)
      return
    }

    if (TransactionAcks.onReceive(event, shardPlayer)) {
      dropWrapperUnlessRewritten(event)
      return
    }

    val isFlying = WrapperPlayClientPlayerFlying.isFlying(event.packetType)

    if (isFlying) {
      shardPlayer.tracking.onTickStart()
    }

    (event.packetType as? PacketType.Play.Client)?.let {
      receiveRoutes[it]?.invoke(event, shardPlayer)
    }

    if (isFlying) {
      flying.handle(event, shardPlayer)
    }

    if (event.isCancelled) {
      if (isFlying) {
        shardPlayer.tracking.onTickAborted()
      }
      flying.resetFlags(shardPlayer)
      return
    }

    flying.syncServerState(event, shardPlayer)

    if (isFlying) {
      flying.onDataTick(shardPlayer)
    }

    clientBrand.onPacketReceive(shardPlayer, event)

    if (isFlying) {
      shardPlayer.tracking.onTickEnd()
    }

    flying.resetFlags(shardPlayer)
    dropWrapperUnlessRewritten(event)
  }

  fun handleSend(event: PacketSendEvent) {
    if (event.packetType == PacketType.Login.Server.LOGIN_SUCCESS) {
      val user = event.user
      event.tasksAfterSend.add(Runnable { playerDataManager.handleUserConnect(user) })
      return
    }
    val shardPlayer = playerDataManager.getPlayer(event.user) ?: return
    (event.packetType as? PacketType.Play.Server)?.let {
      sendRoutes[it]?.invoke(event, shardPlayer)
    }
    dropWrapperUnlessRewritten(event)
  }

  private fun dropWrapperUnlessRewritten(event: ProtocolPacketEvent) {
    if (!event.needsReEncode()) {
      event.setLastUsedWrapper(null)
    }
  }
}
