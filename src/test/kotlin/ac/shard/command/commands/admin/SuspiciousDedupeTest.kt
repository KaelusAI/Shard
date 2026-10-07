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

import ac.shard.network.SuspiciousSnapshot
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SuspiciousDedupeTest {

  @Test
  fun `a player on two servers collapses to the freshest entry`() {
    val uuid = "11111111-1111-1111-1111-111111111111"
    val stale = SuspiciousSnapshot("lite1", uuid, "season", 9.4, 38, 1_000L)
    val fresh = SuspiciousSnapshot("lite2", uuid, "season", 9.4, 43, 2_000L)

    val result = dedupeByPlayer(listOf(stale, fresh))

    assertEquals(1, result.size)
    assertEquals("lite2", result.single().server, "should keep the server with the newest update")
  }

  @Test
  fun `local entry wins over a stale remote one for the same player`() {
    val uuid = "22222222-2222-2222-2222-222222222222"
    val remote = SuspiciousSnapshot("lite1", uuid, "season", 9.4, 38, 9_999L)
    val local = SuspiciousSnapshot("lite2", uuid, "season", 9.4, 43, Long.MAX_VALUE)

    val result = dedupeByPlayer(listOf(remote, local))

    assertEquals(1, result.size)
    assertEquals("lite2", result.single().server)
  }

  @Test
  fun `different players are all kept`() {
    val a = SuspiciousSnapshot("lite1", "a", "alice", 5.0, 10, 1L)
    val b = SuspiciousSnapshot("lite2", "b", "bob", 7.0, 20, 1L)

    assertEquals(2, dedupeByPlayer(listOf(a, b)).size)
  }
}
