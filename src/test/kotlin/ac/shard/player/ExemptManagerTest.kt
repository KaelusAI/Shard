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
package ac.shard.player

import ac.shard.api.event.ShardEvent
import ac.shard.api.event.exemption.ExemptionChangeEvent
import ac.shard.api.event.exemption.ExemptionChangeEvent.Change
import ac.shard.api.exemption.ExemptionScope
import ac.shard.api.impl.CommandInitiator
import ac.shard.api.impl.event.ShardEvents
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.Server
import org.junit.jupiter.api.Test

class ExemptManagerTest {
  private val playerId = UUID.randomUUID()
  private val published = mutableListOf<ShardEvent>()
  private val server =
    mockk<Server> {
      every { getPlayer(playerId) } returns null
      every { getOfflinePlayer(playerId) } returns mockk { every { name } returns "b" }
    }
  private val events =
    mockk<ShardEvents>(relaxed = true) {
      every { wants(any()) } returns true
      every { publish(any()) } answers { published += firstArg<ShardEvent>() }
    }
  private val exempts = ExemptManager(events, server)

  private fun changes() = published.filterIsInstance<ExemptionChangeEvent>()

  @Test
  fun `revoking a grant that has already run out reports an expiry, not a revocation`() {
    val grant =
      exempts.grant(
        CommandInitiator("a"),
        playerId,
        ExemptionScope.ENFORCEMENT,
        Duration.ofMillis(1),
        null,
      )
    Thread.sleep(20)
    published.clear()

    assertFalse(grant.revoke())
    assertEquals(listOf(Change.EXPIRED), changes().map { it.change() })
  }

  @Test
  fun `revoking an active grant reports a revocation`() {
    val grant =
      exempts.grant(CommandInitiator("a"), playerId, ExemptionScope.ENFORCEMENT, null, null)
    published.clear()

    assertTrue(grant.revoke())
    assertEquals(listOf(Change.REVOKED), changes().map { it.change() })
  }

  @Test
  fun `an offline player is named from the server cache instead of the uuid`() {
    exempts.grant(CommandInitiator("a"), playerId, ExemptionScope.ENFORCEMENT, null, null)

    assertEquals("b", changes().single().playerName())
  }
}
