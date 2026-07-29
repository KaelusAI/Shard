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
package ac.shard.player.state

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.player.User
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransactionTrackerStateGateTest {

  @BeforeEach
  fun stubServerVersion() {
    mockkStatic(PacketEvents::class)
    val serverManager = mockk<ServerManager>()
    every { serverManager.version } returns ServerVersion.V_1_21_4
    val api = mockk<PacketEventsAPI<*>>()
    every { api.serverManager } returns serverManager
    every { PacketEvents.getAPI() } returns api
  }

  @AfterEach
  fun releaseServerVersion() {
    unmockkStatic(PacketEvents::class)
  }

  @Test
  fun `sends while the encoder is in play even though the decoder lags behind`() {
    val tracker = TransactionTracker()
    val user = userWith(encoder = ConnectionState.PLAY, decoder = ConnectionState.CONFIGURATION)

    tracker.sendTransaction(user)

    verify(exactly = 1) { user.sendPacket(any()) }
    assertEquals(1, tracker.didWeSendThatTrans.size)
  }

  @Test
  fun `stays silent while the encoder is reconfiguring`() {
    val tracker = TransactionTracker()
    val user = userWith(encoder = ConnectionState.CONFIGURATION, decoder = ConnectionState.PLAY)

    tracker.sendTransaction(user)

    verify(exactly = 0) { user.sendPacket(any()) }
    assertEquals(0, tracker.didWeSendThatTrans.size)
  }

  private fun userWith(encoder: ConnectionState, decoder: ConnectionState): User {
    val user = mockk<User>()
    every { user.encoderState } returns encoder
    every { user.decoderState } returns decoder
    every { user.connectionState } throws
      IllegalStateException("Can't get common connection state: $decoder != $encoder")
    every { user.sendPacket(any()) } just runs
    return user
  }
}
