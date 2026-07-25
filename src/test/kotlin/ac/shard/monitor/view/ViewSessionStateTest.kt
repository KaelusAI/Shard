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
package ac.shard.monitor.view

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewSessionStateTest {
  @Test
  fun `tracked entity ids are replaced and cleaned up consistently`() {
    val targetId = UUID.randomUUID()
    val session = ViewSession(TEST_CONFIG, ViewPlacement.ABOVE_NAME, null)

    session.updateTrackedEntityId(targetId, 17)
    assertEquals(targetId, session.targetIdByEntityId(17))

    session.updateTrackedEntityId(targetId, 23)
    assertNull(session.targetIdByEntityId(17))
    assertEquals(targetId, session.targetIdByEntityId(23))

    session.removeTrackedEntityId(targetId)
    assertNull(session.targetIdByEntityId(23))
  }

  private companion object {
    private val TEST_CONFIG =
      ViewRuntimeConfig(
        updateTicks = 2,
        rebindCycles = 10,
        resyncCycles = 50,
        pingRefreshCycles = 20,
        pingBucketMs = 10,
        placement = ViewPlacement.ABOVE_NAME,
        belowTitle = "<white>AI</white>",
        fallbackProb = "--",
        fallbackBuffer = "--",
        probDecimals = 1,
        bufferDecimals = 1,
        prefixTemplate = "<white>{prob}</white>",
        suffixTemplate = "<white>{buffer}</white>",
        belowTemplate = "<white>{prob}</white>",
        defaultBelowText = "<white>--</white>",
        usesPing = false,
      )
  }
}
