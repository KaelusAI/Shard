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
package ac.shard.packet

import ac.shard.player.ShardPlayer
import ac.shard.player.state.TransactionTracker
import com.github.retrooper.packetevents.event.PacketSendEvent
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TransactionsTest {
  private val tracker = TransactionTracker()
  private val player =
    mockk<ShardPlayer> {
      every { transactions } returns tracker
      every { sendTransaction() } answers { tracker.lastTransactionSent.incrementAndGet() }
    }

  @Test
  fun `the pre transaction id is read after the send`() {
    assertEquals(1, player.preTransaction())
    assertEquals(1, player.lastSentTransaction())
  }

  @Test
  fun `reading the last id does not send`() {
    player.lastSentTransaction()

    assertEquals(0, tracker.lastTransactionSent.get())
  }

  @Test
  fun `a bracket queues exactly one transaction after the packet`() {
    val after = mutableListOf<Runnable>()
    val event = mockk<PacketSendEvent> { every { tasksAfterSend } returns after }

    val id = player.bracketTransaction(event)

    assertEquals(1, id)
    assertEquals(1, after.size)
    after.single().run()
    assertEquals(2, tracker.lastTransactionSent.get())
  }

  @Test
  fun `a spawn sends only when the id was destroyed in this transaction`() {
    assertEquals(0, player.spawnTransaction(7))

    tracker.entitiesDespawnedThisTransaction.add(7)

    assertEquals(1, player.spawnTransaction(7))
  }

  @Test
  fun `only a transaction we sent is counted`() {
    tracker.didWeSendThatTrans.add(-3)
    tracker.entitiesDespawnedThisTransaction.add(7)

    tracker.markSent(-4)
    assertEquals(0, tracker.lastTransactionSent.get())

    tracker.markSent(-3)
    assertEquals(1, tracker.lastTransactionSent.get())
    assertEquals(0, tracker.entitiesDespawnedThisTransaction.size)
    assertEquals(1, tracker.transactionsSent.size)
  }
}
