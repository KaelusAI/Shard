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
import ac.shard.player.state.TransactionTracker
import com.github.retrooper.packetevents.event.PacketSendEvent

internal fun ShardPlayer.lastSentTransaction(): Int = transactions.lastTransactionSent.get()

internal fun ShardPlayer.preTransaction(): Int {
  sendTransaction()
  return lastSentTransaction()
}

internal fun ShardPlayer.bracketTransaction(event: PacketSendEvent): Int {
  val id = preTransaction()
  event.tasksAfterSend.add(Runnable { sendTransaction() })
  return id
}

internal fun ShardPlayer.spawnTransaction(entityId: Int): Int {
  if (transactions.entitiesDespawnedThisTransaction.contains(entityId)) sendTransaction()
  return lastSentTransaction()
}

internal fun TransactionTracker.markSent(id: Short) {
  if (!didWeSendThatTrans.remove(id)) return
  entitiesDespawnedThisTransaction.clear()
  transactionsSent.add(TransactionStamp(id, System.nanoTime()))
  lastTransactionSent.getAndIncrement()
}
