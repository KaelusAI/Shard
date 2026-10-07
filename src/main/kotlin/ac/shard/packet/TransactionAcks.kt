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

import ac.shard.player.ShardPlayer
import ac.shard.player.TransactionStamp
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation

internal object TransactionAcks {
  private const val NANOS_PER_MILLI = 1_000_000L
  private const val PING_EWMA_OLD_WEIGHT = 4
  private const val PING_EWMA_TOTAL_WEIGHT = 5

  fun onReceive(event: PacketReceiveEvent, player: ShardPlayer): Boolean {
    val answered =
      when (event.packetType) {
        PacketType.Play.Client.WINDOW_CONFIRMATION -> {
          val id = WrapperPlayClientWindowConfirmation(event).actionId
          id <= 0 && acknowledge(player, id)
        }
        PacketType.Play.Client.PONG -> {
          val id = WrapperPlayClientPong(event).id
          // a PONG id wider than a short is not ours
          id == id.toShort().toInt() && acknowledge(player, id.toShort())
        }
        else -> null
      }
    if (answered == true) event.isCancelled = true
    return answered != null
  }

  fun onConfirmationSent(event: PacketSendEvent, player: ShardPlayer) {
    val id = WrapperPlayServerWindowConfirmation(event).actionId
    if (id <= 0) player.transactions.markSent(id)
  }

  fun onPingSent(event: PacketSendEvent, player: ShardPlayer) {
    val id = WrapperPlayServerPing(event).id
    if (id == id.toShort().toInt()) player.transactions.markSent(id.toShort())
  }

  private fun acknowledge(player: ShardPlayer, id: Short): Boolean {
    val transactions = player.transactions
    if (transactions.transactionsSent.none { it.id == id }) return false
    var data: TransactionStamp?
    do {
      data = transactions.transactionsSent.poll()
      if (data != null) transactions.lastTransactionReceived.incrementAndGet()
    } while (data != null && data.id != id)

    player.latencyUtils.handleNettySyncTransaction(transactions.lastTransactionReceived.get())
    transactions.lastTransReceivedTime.set(System.currentTimeMillis())

    if (data != null) {
      val rttMs = ((System.nanoTime() - data.timeNanos) / NANOS_PER_MILLI).toInt().coerceAtLeast(0)
      val prev = player.tracking.playerPing
      player.tracking.playerPing =
        if (prev == 0) rttMs else (prev * PING_EWMA_OLD_WEIGHT + rttMs) / PING_EWMA_TOTAL_WEIGHT
    }
    return data != null
  }
}
