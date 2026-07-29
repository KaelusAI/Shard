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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.player.state

import ac.shard.player.TransactionStamp
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation
import it.unimi.dsi.fastutil.ints.IntArraySet
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TransactionTracker {
  val transactionsSent: Queue<TransactionStamp> = ConcurrentLinkedQueue()
  val didWeSendThatTrans: MutableSet<Short> = ConcurrentHashMap.newKeySet<Short>()
  val lastTransactionSent: AtomicInteger = AtomicInteger(0)
  val lastTransactionReceived: AtomicInteger = AtomicInteger(0)
  val lastTransSentTime: AtomicLong = AtomicLong(System.currentTimeMillis())
  val lastTransReceivedTime: AtomicLong = AtomicLong(System.currentTimeMillis())
  val entitiesDespawnedThisTransaction: IntArraySet = IntArraySet()
  private val transactionIdCounter: AtomicInteger = AtomicInteger(0)

  fun sendTransaction(user: User) {
    if (user.encoderState != com.github.retrooper.packetevents.protocol.ConnectionState.PLAY) {
      return
    }

    val raw = transactionIdCounter.getAndIncrement() and 0x7FFF
    val modern =
      PacketEvents.getAPI()
        .serverManager
        .version
        .isNewerThanOrEquals(com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_17)

    val transactionId =
      if (modern) {
        (if (raw == 0) 0x7FFF else raw).toShort()
      } else {
        (-1 * raw).toShort()
      }
    didWeSendThatTrans.add(transactionId)

    val packet =
      if (modern) {
        WrapperPlayServerPing(transactionId.toInt())
      } else {
        WrapperPlayServerWindowConfirmation(0, transactionId, false)
      }
    user.sendPacket(packet)
    lastTransSentTime.set(System.currentTimeMillis())
  }
}
