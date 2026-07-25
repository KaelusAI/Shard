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

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewPingSamplingTest {
  private fun config(
    usesPing: Boolean = true,
    pingRefreshCycles: Int = 3,
    pingBucketMs: Int = 10,
  ): ViewRuntimeConfig =
    ViewRuntimeConfig(
      updateTicks = 2,
      rebindCycles = 10,
      resyncCycles = 50,
      pingRefreshCycles = pingRefreshCycles,
      pingBucketMs = pingBucketMs,
      placement = ViewPlacement.BELOW_NAME,
      belowTitle = "",
      fallbackProb = "--",
      fallbackBuffer = "--",
      probDecimals = 1,
      bufferDecimals = 1,
      prefixTemplate = "{prob}",
      suffixTemplate = "",
      belowTemplate = "{prob}",
      defaultBelowText = "--",
      usesPing = usesPing,
    )

  @Test
  fun `ping is blank when no template asks for it`() {
    val state = TargetTeamState("slv_test")

    assertEquals("", state.resolvePingDisplay(123, config(usesPing = false)))
  }

  @Test
  fun `first call always samples`() {
    val state = TargetTeamState("slv_test")

    assertEquals("57", state.resolvePingDisplay(57, config(pingBucketMs = 1)))
  }

  @Test
  fun `sample is held until the refresh interval elapses`() {
    val state = TargetTeamState("slv_test")
    val config = config(pingRefreshCycles = 3, pingBucketMs = 1)

    assertEquals("50", state.resolvePingDisplay(50, config))
    assertEquals("50", state.resolvePingDisplay(90, config))
    assertEquals("50", state.resolvePingDisplay(90, config))
    assertEquals("50", state.resolvePingDisplay(90, config))
    assertEquals("90", state.resolvePingDisplay(90, config))
  }

  @Test
  fun `movement inside one bucket does not change the sample`() {
    val state = TargetTeamState("slv_test")
    val config = config(pingRefreshCycles = 1, pingBucketMs = 10)

    assertEquals("50", state.resolvePingDisplay(50, config))
    state.resolvePingDisplay(50, config)
    assertEquals("50", state.resolvePingDisplay(57, config))
  }

  @Test
  fun `crossing a bucket boundary updates the sample`() {
    val state = TargetTeamState("slv_test")
    val config = config(pingRefreshCycles = 1, pingBucketMs = 10)

    assertEquals("50", state.resolvePingDisplay(50, config))
    state.resolvePingDisplay(50, config)
    assertEquals("60", state.resolvePingDisplay(60, config))
  }
}
